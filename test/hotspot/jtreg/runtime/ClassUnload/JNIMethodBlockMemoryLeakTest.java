/*
 * Copyright (c) 2021, Google. All rights reserved.
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
 */

/*
 * @test JNIMethodBlockMemoryLeakTest
 * @requires vm.opt.final.ClassUnloading
 * @modules java.base/jdk.internal.misc
 * @library /runtime/testlibrary /test/lib
 * @library classes
 * @build C1 C2
 * @run main/othervm/native -Xbootclasspath/a:. -Xmn8m -XX:+UnlockDiagnosticVMOptions  -XX:NativeMemoryTracking=detail -Xlog:class+unload=trace -agentlib:SimpleAgent JNIMethodBlockMemoryLeakTest
 *
 * To run the test standalone from command line:
 * $ bin/java -cp JTwork/classes/runtime/ClassUnload/JNIMethodBlockMemoryLeakTest.d \
 *     -Dtest.class.path=JTwork/classes/runtime/ClassUnload/JNIMethodBlockMemoryLeakTest.d \
 *     -Xlog:class+unload=trace JNIMethodBlockMemoryLeakTest
 */
import java.util.Iterator;
import jdk.test.lib.Platform;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.JDKToolFinder;
import jtreg.SkippedException;

/**
 * JNIMethodBlockMemoryLeakTest uses NMT (run via jcmd) to examine the
 * allocated memory related to Method::ensure_jmethod_ids. The test
 * is only executed with debug build.
 */
public class JNIMethodBlockMemoryLeakTest {
    private static String class1 = "C1";
    private static String class2 = "C2";

    static {
        // A JVMTI agent is needed in order to exercise the code path
        // for creating jmethod_ids and allocating memory for
        // JNIMethodBlock and JNIMethodBlockNodes.
        try {
            System.loadLibrary("SimpleAgent");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load SimpleAgent library");
            System.err.println("java.library.path:"
                + System.getProperty("java.library.path"));
            throw ule;
        }
    }

    native static void setNotificationMode();

    static ProcessBuilder pb;
    static OutputAnalyzer output;
    static String pid;

    public static void main(String... args) throws Exception {
        if (!Platform.isDebugBuild()) {
          // The stack frames reported in NMT diff may not contain
          // Method::ensure_jmethod_ids (possibly due to inlining?)
          // with non-debug build.
          System.out.println("Skipped.");
          throw new SkippedException("Requires a debug build.");
        }

        // Register for notification of the JVMTI_EVENT_CLASS_PREPARE
        // event in the agent. The agent's OnClassPrepare callback function
        // triggers jmethod_ids creation.
        setNotificationMode();

        // Get native_memory baseline using jcmd
        pb = new ProcessBuilder();
        pid = Long.toString(ProcessTools.getProcessId());

        // Run 'jcmd <pid> VM.native_memory baseline=true'
        pb.command(
            new String[] { JDKToolFinder.getJDKTool("jcmd"), pid,
                           "VM.native_memory", "baseline=true"});
        pb.start().waitFor();

        output = new OutputAnalyzer(pb.start());
        System.out.println(output.getOutput());

        // Do two loading/unloading operations and compares the allocated
        // memory related to Method::ensure_jmethod_ids. A memory leak is
        // detected if allocated memory grows.
        int res1 = runAndCompareMemory();
        int res2 = runAndCompareMemory();
        if (res2 > res1) {
            throw new RuntimeException(
                "Failed. Found memory leak: Result 1: " +
                res1 + "KB; Result 2: " + res2 + "KB");
        }
    }

    private static void run() throws Exception {
        // Load class C1 and C2 via a user defined class loader. The total
        // number of methods is 11, which is larger than the initial
        // capacity (8) of JNIMethodBlock defined in Hotspot. So more than one
        // JNIMethodBlockNodes are created and linked. This gives better
        // coverages for testing freeing the JNIMethodBlock and
        // JNIMethodBlockNodes memory. Although we are using Hotspot internal
        // knowledge of the JNIMethodBlock initial capacity, it does not cause
        // any stability issue of the test.
        ClassLoader loader = ClassUnloadCommon.newClassLoader();
        Class<?> c1 = loader.loadClass(class1);
        Object o1 = c1.newInstance();
        Class<?> c2 = loader.loadClass(class2);
        Object o2 = c2.newInstance();

        loader = null;
        c1 = null;
        o1 = null;
        c2 = null;
        o2 = null;
        ClassUnloadCommon.triggerUnloading();
    }

    // 1. Load and unload C1 and C2, which exercises the code path of memory
    //    allocation for JNIMethodBlock/JNIMethodBlockNode.
    //
    // 2. Run 'jcmd <pid> VM.native_memory detail.diff scale=KB'
    //
    // The return value is the memory allocated (related to
    // Method::ensure_jmethod_ids).
    private static int runAndCompareMemory() throws Exception {
        int num_of_runs = 10;
        for (int i = 0; i < num_of_runs; i++) {
            run();
        }

        pb.command(
            new String[] { JDKToolFinder.getJDKTool("jcmd"), pid,
                           "VM.native_memory", "detail.diff", "scale=KB"});
        output = new OutputAnalyzer(pb.start());
        Iterator<String> it = output.getOutput().lines().iterator();
        int res = 0;
        boolean found_ensure_jmethod_ids = false;

        // Look for the first 'malloc=' after Method::ensure_jmethod_ids
        while (it.hasNext()) {
            if (it.next().contains("Method::ensure_jmethod_ids")) {
                found_ensure_jmethod_ids = true;
                continue;
            }

            if (found_ensure_jmethod_ids) {
                String s = it.next();
                if (s.contains("malloc=")) {
                    int idx = s.indexOf('=') + 1;
                    s = s.substring(idx, s.indexOf("KB ", idx));
                    System.out.println(
                        "Used memory for Method::ensure_jmethod_ids: " + s + "KB");
                    res = Integer.parseInt(s);
                    break;
                }
            }
        }
        return res;
    }
}
