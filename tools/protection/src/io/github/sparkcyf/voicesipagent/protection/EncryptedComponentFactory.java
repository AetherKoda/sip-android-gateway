package io.github.sparkcyf.voicesipagent.protection;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

/** Android's public class-loader hook covers the app, providers, services and receivers. */
public final class EncryptedComponentFactory extends AppComponentFactory {
    private AppComponentFactory delegate;

    @Override public ClassLoader instantiateClassLoader(ClassLoader parent, ApplicationInfo info) {
        return EncryptedCode.load(info.sourceDir,
                EncryptedCode.nativePath(info.sourceDir, info.nativeLibraryDir), parent);
    }

    private synchronized AppComponentFactory factory(ClassLoader loader) {
        // Android may recreate the factory after the class loader has already been
        // installed (including package updates and background service launches).
        if (delegate != null) return delegate;
        try {
            delegate = (AppComponentFactory) loader.loadClass("androidx.core.app.CoreComponentFactory")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Application component factory unavailable", error);
        }
        return delegate;
    }

    @Override public Application instantiateApplication(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return factory(loader).instantiateApplication(loader, name);
    }

    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return factory(loader).instantiateActivity(loader, name, intent);
    }

    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return factory(loader).instantiateService(loader, name, intent);
    }

    @Override public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return factory(loader).instantiateReceiver(loader, name, intent);
    }

    @Override public ContentProvider instantiateProvider(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return factory(loader).instantiateProvider(loader, name);
    }
}
