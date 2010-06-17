/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.io.File;
import vogar.commands.AndroidSdk;
import vogar.commands.Mkdir;

/**
 * Executes actions on a Dalvik VM on a Linux desktop.
 */
public class HostDalvikVm extends Vm {

    private final AndroidSdk androidSdk;
    private final File dalvikCache;
    private String base;

    public HostDalvikVm(Environment environment, Mode.Options options, Options vmOptions,
            AndroidSdk androidSdk) {
        super(environment, options, vmOptions);
        this.androidSdk = androidSdk;
        this.dalvikCache = environment.file("android-data", "dalvik-cache");
    }

    @Override protected void installRunner() {
        // dex everything on the classpath
        for (File classpathElement : classpath.getElements()) {
            dex(androidSdk.basenameOfJar(classpathElement), classpathElement);
        }

        new Mkdir().mkdirs(dalvikCache);
        base = System.getenv("OUT");
        Console.getInstance().verbose("dalvikvm base directory: " + base);
    }

    private File nameDexFile(String name) {
        return environment.file(name, name + ".dx.jar");
    }

    @Override protected void postCompile(Action action, File jar) {
        dex(action.getName(), jar);
    }

    private void dex(String name, File jar) {
        Console.getInstance().verbose("dex " + name);
        androidSdk.dex(nameDexFile(name), Classpath.of(jar));
    }

    @Override protected VmCommandBuilder newVmCommandBuilder(File workingDirectory) {
        Classpath bootClasspath = Classpath.of(
                new File(base + "/system/framework/core.jar"),
                new File(base + "/system/framework/ext.jar"),
                new File(base + "/system/framework/framework.jar")
        );

        return new VmCommandBuilder()
                .env("ANDROID_PRINTF_LOG", "tag")
                .env("ANDROID_LOG_TAGS", "*:w")
                .env("ANDROID_DATA", dalvikCache.getParent())
                .env("ANDROID_ROOT", base + "/system")
                .env("LD_LIBRARY_PATH", base + "/system/lib")
                .env("DYLD_LIBRARY_PATH", base + "/system/lib")
                .vmCommand(base + "/system/bin/dalvikvm")
                .workingDir(workingDirectory)
                .temp(workingDirectory)
                .vmArgs("-Xbootclasspath:" + bootClasspath.toString())
                .vmArgs("-Duser.home=" + workingDirectory)
                .vmArgs("-Duser.language=en")
                .vmArgs("-Duser.region=US")
                .vmArgs("-Djavax.net.ssl.trustStore=" + base + "/system/etc/security/cacerts.bks");
    }

    @Override protected Classpath getRuntimeClasspath(Action action) {
        Classpath result = new Classpath();
        result.addAll(nameDexFile(action.getName()));
        for (File classpathElement : classpath.getElements()) {
            result.addAll(nameDexFile(androidSdk.basenameOfJar(classpathElement)));
        }
        return result;
    }
}