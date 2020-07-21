package com.hprof.bitmap;

import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Heap;
import com.squareup.haha.perflib.HprofParser;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.io.HprofBuffer;
import com.squareup.haha.perflib.io.MemoryMappedFileBuffer;
import com.squareup.leakcanary.AnalysisResult;
import com.squareup.leakcanary.AnalyzerProgressListener;
import com.squareup.leakcanary.ExcludedRefs;
import com.squareup.leakcanary.HeapAnalyzer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;


public class Main {

    public static void main(String[] args) throws IOException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        File heapDumpFile = new File("/Users/tconan/Desktop/heap.hprof");
        heapDumpFile = new File("/Users/tconan/Desktop/memory-20200721T103237.hprof");
        HprofBuffer hprofBuffer = new MemoryMappedFileBuffer(heapDumpFile);
        HprofParser parser = new HprofParser(hprofBuffer);
        Snapshot snapshot = parser.parse();

        String className = "android.graphics.Bitmap";
        ClassObj bitmapClass = snapshot.findClass(className);

        List<Instance> bitmapInstances = bitmapClass.getInstancesList();

        for (Instance instance : bitmapInstances) {
            Heap heap = instance.getHeap();
            String heapName = heap.getName();

            if (!heapName.equalsIgnoreCase("app")) {
                continue;
            }

            ArrayInstance buffer = HahaHelper.fieldValue(((ClassInstance) instance).getValues(), "mBuffer");
            Integer height = HahaHelper.fieldValue(((ClassInstance) instance).getValues(), "mHeight");
            Integer width = HahaHelper.fieldValue(((ClassInstance) instance).getValues(), "mWidth");

            Class<?> personType = buffer.getClass();
            Method method = personType.getDeclaredMethod("asRawByteArray", int.class, int.class);
            method.setAccessible(true);
            byte[] data = (byte[]) method.invoke(buffer, 0, buffer.getValues().length);

            String str = new String(Base64.getEncoder().encode(data));
            String hash = md5(str);

            List<ReportBean> beanList = reportBeanMap.get(hash);
            if (beanList == null) {
                beanList = new ArrayList<>();
                reportBeanMap.put(hash, beanList);
            }

            ReportBean bean = new ReportBean();
            bean.setBufferHash(hash);
            bean.setHeight(height);
            bean.setWidth(width);
            bean.setBufferSize(buffer.getSize());
            bean.setDuplcateCount(0);
            bean.setStacks(getStack(instance, snapshot));
            bean.setData(data);
            beanList.add(bean);

            reportBeanMap.put(hash, beanList);
        }

        for (Map.Entry<String, List<ReportBean>> entry : reportBeanMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                System.out.println("-------------------------------------------------------");
                for (ReportBean bean : entry.getValue()) {
                    String dir = heapDumpFile.getParent() + File.separator + "images";
                    File file = new File(dir);
                    if (!file.exists()) {
                        file.mkdir();
                    }
                    String path = dir + File.separator + bean.getBufferHash() + ".png";
                    ARGB8888_BitmapExtractor.getImage(bean.getWidth(), bean.getHeight(), bean.getData(), path);
                    bean.setData(null);
                    System.out.println(bean.toString());
                }
            }
        }
    }


    public static Map<String, List<ReportBean>> reportBeanMap = new HashMap<>();

    /**
     * 计算 md5
     *
     * @param string
     * @return
     */
    public static String md5(String string) {
        MessageDigest md5 = null;
        try {
            md5 = MessageDigest.getInstance("MD5");
            byte[] bytes = md5.digest(string.getBytes());
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) {
                String temp = Integer.toHexString(b & 0xff);
                if (temp.length() == 1) {
                    temp = "0" + temp;
                }
                result.append(temp);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 根据 byte[] 保存图片到文件
     * 参考
     * https://github.com/JetBrains/adt-tools-base/blob/master/ddmlib/src/main/java/com/android/ddmlib/BitmapDecoder.java
     */
    private static class ARGB8888_BitmapExtractor {

        public static void getImage(int width, int height, byte[] rgba, String pngFilePath) throws IOException {
            BufferedImage bufferedImage = new BufferedImage(width, height,
                    BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < height; y++) {
                int stride = y * width;
                for (int x = 0; x < width; x++) {
                    int i = (stride + x) * 4;
                    long rgb = 0;
                    rgb |= ((long) rgba[i] & 0xff) << 16; // r
                    rgb |= ((long) rgba[i + 1] & 0xff) << 8;  // g
                    rgb |= ((long) rgba[i + 2] & 0xff);       // b
                    rgb |= ((long) rgba[i + 3] & 0xff) << 24; // a
                    bufferedImage.setRGB(x, y, (int) (rgb & 0xffffffffL));
                }
            }
            File outputfile = new File(pngFilePath);
            ImageIO.write(bufferedImage, "png", outputfile);

        }
    }

    /**
     * 获取 Instance 的 stack
     *
     * @param instance
     * @param snapshot
     * @return
     */
    public static String getStack(Instance instance, Snapshot snapshot) {

        String stacks = "";

        ExcludedRefs NO_EXCLUDED_REFS = ExcludedRefs.builder().build();
        HeapAnalyzer heapAnalyzer = new HeapAnalyzer(NO_EXCLUDED_REFS, AnalyzerProgressListener.NONE,
                Collections.emptyList());

        Class<?> heapAnalyzerClass = heapAnalyzer.getClass();

        try {
            Method method = heapAnalyzerClass.getDeclaredMethod("findLeakTrace",
                    long.class,
                    Snapshot.class,
                    Instance.class,
                    boolean.class);

            method.setAccessible(true);

            long analysisStartNanoTime = System.nanoTime();

            AnalysisResult analysisResult = (AnalysisResult) method.invoke(heapAnalyzer,
                    analysisStartNanoTime,
                    snapshot,
                    instance,
                    false);


            String string = analysisResult.leakTrace.toString();

            stacks = string;

        } catch (Exception e) {

            System.out.println("Exception =" + e.getMessage());

        }

        return stacks;

    }
}
