package com.chenan.flymebobo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * flymebobo 2.0 — serves downloaded update APKs to the system
 * installer (v521, in-app update).
 *
 * This build has no androidx, so no androidx FileProvider; this
 * tiny provider does the same job for exactly one purpose:
 * read-only access to APK files directly inside
 * cacheDir/update/. exported=false and the install intent
 * carries a per-URI read grant, so nothing else can read
 * through it.
 */
public class ApkFileProvider extends ContentProvider {

    public static final String AUTHORITY =
            "com.chenan.flymebobo.apkprovider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        try {
            File base = new File(getContext().getCacheDir(),
                    "update");
            File f = new File(base, uri.getLastPathSegment());
            String basePath = base.getCanonicalPath();
            if (!f.getCanonicalPath().startsWith(
                    basePath + File.separator)) {
                throw new FileNotFoundException("outside update dir");
            }
            if (!f.isFile()) {
                throw new FileNotFoundException(f.getName());
            }
            return ParcelFileDescriptor.open(f,
                    ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            throw new FileNotFoundException(String.valueOf(t));
        }
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection,
            String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection,
            String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values,
            String selection, String[] selectionArgs) {
        return 0;
    }
}
