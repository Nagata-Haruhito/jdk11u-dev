/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

/*
 * @test
 * @summary Test that CDS still works when the JDK is moved to a new directory
 * @bug 8272345
 * @requires vm.cds
 * @comment This test doesn't work on Windows because it depends on symlinks
 * @requires os.family != "windows"
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management
 *          jdk.jartool/sun.tools.jar
 * @compile test-classes/Hello.java
 * @run driver MoveJDKTest
 */

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import jdk.test.lib.process.OutputAnalyzer;

public class MoveJDKTest {
    public static void main(String[] args) throws Exception {
        String java_home_src = System.getProperty("java.home");
        String java_home_dst = System.getProperty("user.dir") + File.separator + "moved_jdk";

        TestCommon.startNewArchiveName();
        String jsaFile = TestCommon.getCurrentArchiveName();
        String jsaOpt = "-XX:SharedArchiveFile=" + jsaFile;
        {
            ProcessBuilder pb = makeBuilder(java_home_src + "/bin/java", "-Xshare:dump", jsaOpt);
            TestCommon.executeAndLog(pb, "dump");
        }
        {
            ProcessBuilder pb = makeBuilder(java_home_src + "/bin/java",
                                            "-Xshare:auto",
                                            jsaOpt,
                                            "-Xlog:class+path=info",
                                            "-version");
            OutputAnalyzer out = TestCommon.executeAndLog(pb, "exec-src");
            out.shouldNotContain("shared class paths mismatch");
            out.shouldNotContain("BOOT classpath mismatch");
        }

        clone(new File(java_home_src), new File(java_home_dst));
        System.out.println("============== Cloned JDK at " + java_home_dst);

        // Test runtime with cloned JDK
        {
            ProcessBuilder pb = makeBuilder(java_home_dst + "/bin/java",
                                            "-Xshare:auto",
                                            jsaOpt,
                                            "-Xlog:class+path=info",
                                            "-version");
            OutputAnalyzer out = TestCommon.executeAndLog(pb, "exec-dst");
            out.shouldNotContain("shared class paths mismatch");
            out.shouldNotContain("BOOT classpath mismatch");
        }

        // Test with bad JAR file name, hello.modules
        String helloJar = JarBuilder.getOrCreateHelloJar();
        String fake_modules = copyFakeModulesFromHelloJar();
        String dumptimeBootAppendOpt = "-Xbootclasspath/a:" + fake_modules;
        {
            ProcessBuilder pb = makeBuilder(java_home_src + "/bin/java",
                                            "-Xshare:dump",
                                            dumptimeBootAppendOpt,
                                            jsaOpt);
            TestCommon.executeAndLog(pb, "dump");
        }
        {
            String runtimeBootAppendOpt = dumptimeBootAppendOpt + System.getProperty("path.separator") + helloJar;
            ProcessBuilder pb = makeBuilder(java_home_dst + "/bin/java",
                                            "-Xshare:auto",
                                            runtimeBootAppendOpt,
                                            jsaOpt,
                                            "-Xlog:class+path=info",
                                            "-version");
            OutputAnalyzer out = TestCommon.executeAndLog(pb, "exec-dst");
            out.shouldNotContain("shared class paths mismatch");
            out.shouldNotContain("BOOT classpath mismatch");
        }
    }

    // Do a cheap clone of the JDK. Most files can be sym-linked. However, $JAVA_HOME/bin/java and $JAVA_HOME/lib/.../libjvm.so"
    // must be copied, because the java.home property is derived from the canonicalized paths of these 2 files.
    static void clone(File src, File dst) throws Exception {
        if (dst.exists()) {
            if (!dst.isDirectory()) {
                throw new RuntimeException("Not a directory :" + dst);
            }
        } else {
            if (!dst.mkdir()) {
                throw new RuntimeException("Cannot create directory: " + dst);
            }
        }
        final String jvmLib = System.mapLibraryName("jvm");
        for (String child : src.list()) {
            if (child.equals(".") || child.equals("..")) {
                continue;
            }

            File child_src = new File(src, child);
            File child_dst = new File(dst, child);
            if (child_dst.exists()) {
                throw new RuntimeException("Already exists: " + child_dst);
            }
            if (child_src.isFile()) {
                if (child.equals(jvmLib) || child.equals("java")) {
                    Files.copy(child_src.toPath(), /* copy data to -> */ child_dst.toPath());
                } else {
                    Files.createSymbolicLink(child_dst.toPath(),  /* link to -> */ child_src.toPath());
                }
            } else {
                clone(child_src, child_dst);
            }
        }
    }

    static ProcessBuilder makeBuilder(String... args) throws Exception {
        System.out.print("[");
        for (String s : args) {
            System.out.print(" " + s);
        }
        System.out.println(" ]");
        return new ProcessBuilder(args);
    }

    private static String copyFakeModulesFromHelloJar() throws Exception {
        String classDir = System.getProperty("test.classes");
        String newFile = "hello.modules";
        String path = classDir + File.separator + newFile;

        Files.copy(Paths.get(classDir, "hello.jar"),
            Paths.get(classDir, newFile),
            StandardCopyOption.REPLACE_EXISTING);
        return path;
    }
}
