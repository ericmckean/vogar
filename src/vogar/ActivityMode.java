/*
 * Copyright (C) 2009 The Android Open Source Project
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

package vogar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import vogar.commands.AndroidSdk;
import vogar.commands.Command;

/**
 * Runs an action in the context of an android.app.Activity on a device
 */
final class ActivityMode extends Mode {

    private static final Logger logger = Logger.getLogger(ActivityMode.class.getName());

    private static final String TEST_ACTIVITY_CLASS = "vogar.target.TestActivity";

    private AndroidSdk androidSdk;
    private File keystore;

    ActivityMode(Integer debugPort, Classpath buildClasspath, List<String> javacArgs,
            int monitorPort, File localTemp, boolean cleanBefore, boolean cleanAfter,
            File deviceRunnerDir, Classpath classpath, AndroidSdk androidSdk) {
        super(new EnvironmentDevice(cleanBefore, cleanAfter,
                debugPort, monitorPort, localTemp, deviceRunnerDir, androidSdk),
                buildClasspath, javacArgs, monitorPort, classpath);
        this.androidSdk = androidSdk;
    }

    private EnvironmentDevice getEnvironmentDevice() {
        return (EnvironmentDevice) environment;
    }

    @Override protected void prepare() {
        super.prepare();
        extractKeystoreToFile();
    }

    private void extractKeystoreToFile() {
        try {
            keystore = environment.file("activity", "vogar.keystore");
            keystore.getParentFile().mkdirs();
            logger.fine("extracting keystore to " + keystore);
            InputStream in = new BufferedInputStream(
                    getClass().getResourceAsStream("/vogar/vogar.keystore"));
            OutputStream out = new BufferedOutputStream(new FileOutputStream(keystore));
            byte[] buf = new byte[1024];
            int count;
            while ((count = in.read(buf)) != -1) {
                out.write(buf, 0, count);
            }
            out.close();
            in.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override protected void postCompile(Action action, File jar) {
        logger.fine("aapt and push " + action.getName());

        // We can't put multiple dex files in one apk.
        // We can't just give dex multiple jars with conflicting class names

        // With that in mind, the APK packaging strategy is as follows:
        // 1. dx to create a dex
        // 2. aapt the dex to create apk
        // 3. sign the apk
        // 4. install the apk
        File dex = createDex(action, jar);
        File apk = createApk(action, dex);
        signApk(apk);
        installApk(action, apk);
    }

    /**
     * Returns a single dexfile containing {@code action}'s classes and all
     * dependencies.
     */
    private File createDex(Action action, File actionJar) {
        File dex = environment.file(action, "classes.dex");
        Classpath classesToDex = Classpath.of(actionJar);
        classesToDex.addAll(this.classpath);
        androidSdk.dex(dex, classesToDex);
        return dex;
    }

    /**
     * According to android.content.pm.PackageParser, package name
     * "must have at least one '.' separator" Since the qualified name
     * may not contain a dot, we prefix containing one to ensure we
     * are compliant.
     */
    private static String packageName(Action action) {
        return "vogar.test." + action.getName();
    }

    private File createApk (Action action, File dex) {
        String androidManifest =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
            "      package=\"" + packageName(action) + "\">\n" +
            "    <uses-permission android:name=\"android.permission.INTERNET\" />\n" +
            "    <application>\n" +
            "        <activity android:name=\"" + TEST_ACTIVITY_CLASS + "\">\n" +
            "            <intent-filter>\n" +
            "                <action android:name=\"android.intent.action.MAIN\" />\n" +
            "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
            "            </intent-filter>\n" +
            "        </activity>\n" +
            "    </application>\n" +
            "</manifest>\n";
        File androidManifestFile = environment.file(action, "classes", "AndroidManifest.xml");
        try {
            FileOutputStream androidManifestOut =
                    new FileOutputStream(androidManifestFile);
            androidManifestOut.write(androidManifest.getBytes("UTF-8"));
            androidManifestOut.close();
        } catch (IOException e) {
            throw new RuntimeException("Problem writing " + androidManifestFile, e);
        }

        File apk = environment.file(action, action + ".apk");
        androidSdk.packageApk(apk, androidManifestFile);
        androidSdk.addToApk(apk, dex);
        androidSdk.addToApk(apk, environment.file(action, "classes", TestProperties.FILE));
        return apk;
    }

    private void signApk(File apkUnsigned) {
        /*
         * key generated with this command, using "password" for the key and keystore passwords:
         *     keytool -genkey -v -keystore src/vogar/vogar.keystore \
         *         -keyalg RSA -validity 10000 -alias vogar
         */
        new Command("jarsigner",
                "--storepass", "password",
                "-keystore", keystore.getPath(),
                apkUnsigned.getPath(),
                "vogar")
                .execute();
    }

    private void installApk(Action action, File apkSigned) {
        // install the local apk ona the device
        getEnvironmentDevice().androidSdk.uninstall(packageName(action));
        getEnvironmentDevice().androidSdk.install(apkSigned);
    }

    @Override protected void fillInProperties(Properties properties, Action action) {
        super.fillInProperties(properties, action);
        properties.setProperty(TestProperties.DEVICE_RUNNER_DIR, getEnvironmentDevice().runnerDir.getPath());
    }

    @Override protected Command createActionCommand(Action action) {
        return new Command(
                "adb", "shell", "am", "start", "-W",
                "-a", "android.intent.action.MAIN",
                "-n", (packageName(action) + "/" + TEST_ACTIVITY_CLASS));
    }

    @Override void cleanup(Action action) {
        super.cleanup(action);
        if (environment.cleanAfter) {
            getEnvironmentDevice().androidSdk.uninstall(action.getName());
        }
    }
}