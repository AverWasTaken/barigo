/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.performance;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

public final class PairedBench {
    static final class Loader extends URLClassLoader {
        Loader(String classpathFile) throws Exception {
            super(urls(classpathFile), PairedBench.class.getClassLoader());
        }
        static URL[] urls(String classpathFile) throws Exception {
            ArrayList<URL> urls = new ArrayList<>();
            // the headless settings shim comes first in both loaders
            urls.add(PairedBench.class.getProtectionDomain().getCodeSource().getLocation());
            for (String item : Files.readString(Path.of(classpathFile)).split(File.pathSeparator)) {
                urls.add(Path.of(item).toUri().toURL());
            }
            return urls.toArray(URL[]::new);
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("baritone.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
    static String time(Method run, String kind, int batch, long[] metrics) throws Exception {
        var alloc = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long bytes = alloc.getCurrentThreadAllocatedBytes(), start = System.nanoTime();
        String value = null;
        for (int i = 0; i < batch; i++) {
            String next = (String) run.invoke(null, kind);
            if (value != null && !value.equals(next)) throw new AssertionError("unstable result");
            value = next;
        }
        metrics[0] = System.nanoTime() - start;
        metrics[1] = alloc.getCurrentThreadAllocatedBytes() - bytes;
        return value;
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        int size = Integer.parseInt(args[2]), warmup = Integer.parseInt(args[3]), samples = Integer.parseInt(args[4]);
        try (Loader a = new Loader(args[0]); Loader b = new Loader(args[1])) {
            Class<?> ca = a.loadClass("baritone.pathing.calc.WalkingBench"), cb = b.loadClass("baritone.pathing.calc.WalkingBench");
            Method ma = ca.getMethod("main", String[].class), mb = cb.getMethod("main", String[].class);
            Method ra = ca.getDeclaredMethod("run", String.class), rb = cb.getDeclaredMethod("run", String.class);
            Field fa = ca.getDeclaredField("lastMisses"), fb = cb.getDeclaredField("lastMisses");
            ra.setAccessible(true); rb.setAccessible(true); fa.setAccessible(true); fb.setAccessible(true);
            for (String kind : "short;flat;obstacles;terraces;mining;composite".split(";")) {
                ma.invoke(null, (Object) new String[]{kind, String.valueOf(warmup), "0", "65536"});
                mb.invoke(null, (Object) new String[]{kind, String.valueOf(warmup), "0", String.valueOf(size)});
                int batch = kind.equals("short") ? 100 : 1;
                for (int i = 0; i < samples; i++) {
                    long[] am = new long[2], bm = new long[2];
                    String av, bv;
                    if ((i & 1) == 0) { av = time(ra, kind, batch, am); bv = time(rb, kind, batch, bm); }
                    else { bv = time(rb, kind, batch, bm); av = time(ra, kind, batch, am); }
                    if (!av.equals(bv)) throw new AssertionError("baseline/candidate result mismatch");
                    System.out.printf("PAIR,%d,%s,%d,%.6f,%.6f,%d,%d,%d,%d,%s%n", size, kind, i,
                            am[0] / 1e6 / batch, bm[0] / 1e6 / batch, am[1] / batch, bm[1] / batch,
                            fa.getLong(null), fb.getLong(null), av);
                }
            }
        }
    }
}
