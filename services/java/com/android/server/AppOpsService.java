/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.app.IAppOpsService;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

public class AppOpsService extends IAppOpsService.Stub {
    static final String TAG = "AppOps";
    static final boolean DEBUG = false;

    // Write at most every 30 minutes.
    static final long WRITE_DELAY = DEBUG ? 1000 : 30*60*1000;

    Context mContext;
    final AtomicFile mFile;
    final Handler mHandler;

    boolean mWriteScheduled;
    final Runnable mWriteRunner = new Runnable() {
        public void run() {
            synchronized (AppOpsService.this) {
                mWriteScheduled = false;
                AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
                    @Override protected Void doInBackground(Void... params) {
                        writeState();
                        return null;
                    }
                };
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[])null);
            }
        }
    };

    final SparseArray<HashMap<String, Ops>> mUidOps
            = new SparseArray<HashMap<String, Ops>>();

    final static class Ops extends SparseArray<Op> {
        public final String packageName;
        public final int uid;

        public Ops(String _packageName, int _uid) {
            packageName = _packageName;
            uid = _uid;
        }
    }

    final static class Op {
        public final int op;
        public int duration;
        public long time;
        public int nesting;

        public Op(int _op) {
            op = _op;
        }
    }

    public AppOpsService(File storagePath) {
        mFile = new AtomicFile(storagePath);
        mHandler = new Handler();
        readState();
    }
    
    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService(Context.APP_OPS_SERVICE, asBinder());
    }

    public void shutdown() {
        Slog.w(TAG, "Writing app ops before shutdown...");
        boolean doWrite = false;
        synchronized (this) {
            if (mWriteScheduled) {
                mWriteScheduled = false;
                doWrite = true;
            }
        }
        if (doWrite) {
            writeState();
        }
    }

    private ArrayList<AppOpsManager.OpEntry> collectOps(Ops pkgOps, int[] ops) {
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        if (ops == null) {
            resOps = new ArrayList<AppOpsManager.OpEntry>();
            for (int j=0; j<pkgOps.size(); j++) {
                Op curOp = pkgOps.valueAt(j);
                resOps.add(new AppOpsManager.OpEntry(curOp.op, curOp.time,
                        curOp.duration));
            }
        } else {
            for (int j=0; j<ops.length; j++) {
                Op curOp = pkgOps.get(ops[j]);
                if (curOp != null) {
                    if (resOps == null) {
                        resOps = new ArrayList<AppOpsManager.OpEntry>();
                    }
                    resOps.add(new AppOpsManager.OpEntry(curOp.op, curOp.time,
                            curOp.duration));
                }
            }
        }
        return resOps;
    }

    @Override
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        ArrayList<AppOpsManager.PackageOps> res = null;
        synchronized (this) {
            for (int i=0; i<mUidOps.size(); i++) {
                HashMap<String, Ops> packages = mUidOps.valueAt(i);
                for (Ops pkgOps : packages.values()) {
                    ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
                    if (resOps != null) {
                        if (res == null) {
                            res = new ArrayList<AppOpsManager.PackageOps>();
                        }
                        AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                                pkgOps.packageName, pkgOps.uid, resOps);
                        res.add(resPackage);
                    }
                }
            }
        }
        return res;
    }

    @Override
    public List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName,
            int[] ops) {
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        synchronized (this) {
            Ops pkgOps = getOpsLocked(uid, packageName, false);
            if (pkgOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
            if (resOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.PackageOps> res = new ArrayList<AppOpsManager.PackageOps>();
            AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                    pkgOps.packageName, pkgOps.uid, resOps);
            res.add(resPackage);
            return res;
        }
    }

    @Override
    public int checkOperation(int code, int uid, String packageName) {
        uid = handleIncomingUid(uid);
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName, false);
            if (op == null) {
                return AppOpsManager.MODE_ALLOWED;
            }
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public int noteOperation(int code, int uid, String packageName) {
        uid = handleIncomingUid(uid);
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName, true);
            if (op == null) {
                return AppOpsManager.MODE_IGNORED;
            }
            if (op.duration == -1) {
                Slog.w(TAG, "Noting op not finished: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + op.time + " duration=" + op.duration);
            }
            op.time = System.currentTimeMillis();
            op.duration = 0;
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public int startOperation(int code, int uid, String packageName) {
        uid = handleIncomingUid(uid);
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName, true);
            if (op == null) {
                return AppOpsManager.MODE_IGNORED;
            }
            if (op.nesting == 0) {
                op.time = System.currentTimeMillis();
                op.duration = -1;
            }
            op.nesting++;
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void finishOperation(int code, int uid, String packageName) {
        uid = handleIncomingUid(uid);
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName, true);
            if (op == null) {
                return;
            }
            if (op.nesting <= 1) {
                if (op.nesting == 1) {
                    op.duration = (int)(System.currentTimeMillis() - op.time);
                } else {
                    Slog.w(TAG, "Finishing op nesting under-run: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + op.time + " duration=" + op.duration
                        + " nesting=" + op.nesting);
                }
                op.nesting = 0;
            } else {
                op.nesting--;
            }
        }
    }

    private int handleIncomingUid(int uid) {
        if (uid == Binder.getCallingUid()) {
            return uid;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return uid;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        return uid;
    }

    private Ops getOpsLocked(int uid, String packageName, boolean edit) {
        HashMap<String, Ops> pkgOps = mUidOps.get(uid);
        if (pkgOps == null) {
            if (!edit) {
                return null;
            }
            pkgOps = new HashMap<String, Ops>();
            mUidOps.put(uid, pkgOps);
        }
        Ops ops = pkgOps.get(packageName);
        if (ops == null) {
            if (!edit) {
                return null;
            }
            // This is the first time we have seen this package name under this uid,
            // so let's make sure it is valid.
            final long ident = Binder.clearCallingIdentity();
            try {
                int pkgUid = -1;
                try {
                    pkgUid = mContext.getPackageManager().getPackageUid(packageName,
                            UserHandle.getUserId(uid));
                } catch (NameNotFoundException e) {
                }
                if (pkgUid != uid) {
                    // Oops!  The package name is not valid for the uid they are calling
                    // under.  Abort.
                    Slog.w(TAG, "Bad call: specified package " + packageName
                            + " under uid " + uid + " but it is really " + pkgUid);
                    return null;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            ops = new Ops(packageName, uid);
            pkgOps.put(packageName, ops);
        }
        return ops;
    }

    private Op getOpLocked(int code, int uid, String packageName, boolean edit) {
        Ops ops = getOpsLocked(uid, packageName, edit);
        if (ops == null) {
            return null;
        }
        Op op = ops.get(code);
        if (op == null) {
            if (!edit) {
                return null;
            }
            op = new Op(code);
            ops.put(code, op);
        }
        if (edit && !mWriteScheduled) {
            mWriteScheduled = true;
            mHandler.postDelayed(mWriteRunner, WRITE_DELAY);
        }
        return op;
    }

    void readState() {
        synchronized (mFile) {
            synchronized (this) {
                FileInputStream stream;
                try {
                    stream = mFile.openRead();
                } catch (FileNotFoundException e) {
                    Slog.i(TAG, "No existing app ops " + mFile.getBaseFile() + "; starting empty");
                    return;
                }
                boolean success = false;
                try {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(stream, null);
                    int type;
                    while ((type = parser.next()) != XmlPullParser.START_TAG
                            && type != XmlPullParser.END_DOCUMENT) {
                        ;
                    }

                    if (type != XmlPullParser.START_TAG) {
                        throw new IllegalStateException("no start tag found");
                    }

                    int outerDepth = parser.getDepth();
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                            && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                        if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                            continue;
                        }

                        String tagName = parser.getName();
                        if (tagName.equals("pkg")) {
                            readPackage(parser);
                        } else {
                            Slog.w(TAG, "Unknown element under <app-ops>: "
                                    + parser.getName());
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }
                    success = true;
                } catch (IllegalStateException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (NullPointerException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (XmlPullParserException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (IndexOutOfBoundsException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } finally {
                    if (!success) {
                        mUidOps.clear();
                    }
                    try {
                        stream.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
    }

    void readPackage(XmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        String pkgName = parser.getAttributeValue(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("uid")) {
                readUid(parser, pkgName);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    void readUid(XmlPullParser parser, String pkgName) throws NumberFormatException,
            XmlPullParserException, IOException {
        int uid = Integer.parseInt(parser.getAttributeValue(null, "n"));
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                Op op = new Op(Integer.parseInt(parser.getAttributeValue(null, "n")));
                op.time = Long.parseLong(parser.getAttributeValue(null, "t"));
                op.duration = Integer.parseInt(parser.getAttributeValue(null, "d"));
                HashMap<String, Ops> pkgOps = mUidOps.get(uid);
                if (pkgOps == null) {
                    pkgOps = new HashMap<String, Ops>();
                    mUidOps.put(uid, pkgOps);
                }
                Ops ops = pkgOps.get(pkgName);
                if (ops == null) {
                    ops = new Ops(pkgName, uid);
                    pkgOps.put(pkgName, ops);
                }
                ops.put(op.op, op);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    void writeState() {
        synchronized (mFile) {
            List<AppOpsManager.PackageOps> allOps = getPackagesForOps(null);

            FileOutputStream stream;
            try {
                stream = mFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state: " + e);
                return;
            }

            try {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, "utf-8");
                out.startDocument(null, true);
                out.startTag(null, "app-ops");

                if (allOps != null) {
                    String lastPkg = null;
                    for (int i=0; i<allOps.size(); i++) {
                        AppOpsManager.PackageOps pkg = allOps.get(i);
                        if (!pkg.getPackageName().equals(lastPkg)) {
                            if (lastPkg != null) {
                                out.endTag(null, "pkg");
                            }
                            lastPkg = pkg.getPackageName();
                            out.startTag(null, "pkg");
                            out.attribute(null, "n", lastPkg);
                        }
                        out.startTag(null, "uid");
                        out.attribute(null, "n", Integer.toString(pkg.getUid()));
                        List<AppOpsManager.OpEntry> ops = pkg.getOps();
                        for (int j=0; j<ops.size(); j++) {
                            AppOpsManager.OpEntry op = ops.get(j);
                            out.startTag(null, "op");
                            out.attribute(null, "n", Integer.toString(op.getOp()));
                            out.attribute(null, "t", Long.toString(op.getTime()));
                            out.attribute(null, "d", Integer.toString(op.getDuration()));
                            out.endTag(null, "op");
                        }
                        out.endTag(null, "uid");
                    }
                    if (lastPkg != null) {
                        out.endTag(null, "pkg");
                    }
                }

                out.endTag(null, "app-ops");
                out.endDocument();
                mFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state, restoring backup.", e);
                mFile.failWrite(stream);
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ApOps service from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (this) {
            pw.println("Current AppOps Service state:");
            for (int i=0; i<mUidOps.size(); i++) {
                pw.print("  Uid "); UserHandle.formatUid(pw, mUidOps.keyAt(i)); pw.println(":");
                HashMap<String, Ops> pkgOps = mUidOps.valueAt(i);
                for (Ops ops : pkgOps.values()) {
                    pw.print("    Package "); pw.print(ops.packageName); pw.println(":");
                    for (int j=0; j<ops.size(); j++) {
                        Op op = ops.valueAt(j);
                        pw.print("      "); pw.print(AppOpsManager.opToString(op.op));
                        pw.print(": time=");
                        TimeUtils.formatDuration(System.currentTimeMillis()-op.time, pw);
                        pw.print(" ago");
                        if (op.duration == -1) {
                            pw.println(" (running)");
                        } else {
                            pw.print("; duration=");
                                    TimeUtils.formatDuration(op.duration, pw);
                                    pw.println();
                        }
                    }
                }
            }
        }
    }
}