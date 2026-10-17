package com.nartya.app;

import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.nartya.app.player.PlayerPlugin;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AndroidSmokeTest {
    @Test public void launchesWithNativeBridgeAndAdaptiveOrientation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.getBridge());
                assertNotNull(activity.getBridge().getPlugin("NartyaProxy"));
                assertNotNull(activity.getBridge().getPlugin("NartyaUpdater"));
                int expected = activity.getResources().getConfiguration().smallestScreenWidthDp >= 600
                    ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                assertEquals(expected, activity.getRequestedOrientation());
                if (Build.VERSION.SDK_INT >= 26 && activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    activity.getBridge().getPlugin("NartyaPlayer").getInstance();
                    assertEquals(3, PlayerPlugin.pipParams().getActions().size());
                }
            });
        }
    }

    @Test public void updaterRejectsTheInstalledVersionAndAnotherPackage() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Object updater = activity.getBridge().getPlugin("NartyaUpdater").getInstance();
                assertNotNull(updater);
                try {
                    java.lang.reflect.Method validate = updater.getClass().getDeclaredMethod("validateCandidate", java.io.File.class);
                    validate.setAccessible(true);
                    java.io.File installed = new java.io.File(activity.getApplicationInfo().sourceDir);
                    java.lang.reflect.InvocationTargetException versionError = assertThrows(
                        java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(updater, installed));
                    assertTrue(versionError.getCause() instanceof SecurityException);
                    assertTrue(versionError.getCause().getMessage().contains("récente"));
                    java.io.File testPackage = new java.io.File(androidx.test.platform.app.InstrumentationRegistry
                        .getInstrumentation().getContext().getApplicationInfo().sourceDir);
                    java.lang.reflect.InvocationTargetException packageError = assertThrows(
                        java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(updater, testPackage));
                    assertTrue(packageError.getCause() instanceof SecurityException);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
        }
    }
}
