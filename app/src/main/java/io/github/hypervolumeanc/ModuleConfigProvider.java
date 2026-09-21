package io.github.hypervolumeanc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Read-only bridge that lets the scoped processes fetch the module options. */
public final class ModuleConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.hypervolumeanc.config";
    public static final String ACTION_CONFIG_CHANGED = "io.github.hypervolumeanc.action.CONFIG_CHANGED";
    public static final String METHOD_GET = "get";
    public static final String EXTRA_MODULE_ENABLED = "module_enabled";
    public static final String EXTRA_CYCLE_INCLUDE_OFF = "cycle_include_off";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        Context context = getContext();
        if (context == null) {
            return result;
        }
        result.putBoolean(EXTRA_MODULE_ENABLED, Settings.moduleEnabled(context));
        result.putBoolean(EXTRA_CYCLE_INCLUDE_OFF, Settings.cycleIncludesOff(context));
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
