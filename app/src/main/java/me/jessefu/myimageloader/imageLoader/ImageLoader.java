package me.jessefu.myimageloader.imageLoader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Jesse Fu on 2018-04-10.
 */

public class ImageLoader {
    private static final String TAG = "ImageLoader";

    private static final String MD5 = "MD5";
    private static final int DISK_CACHE_SIZE = 50 * 1024 * 1024;
    private static final int DISK_CACHE_INDEX = 0;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();//cpu数量

    private static final int IO_BUFFER_SIZE = 8 * 1024;

    private Context mContext;
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskCache;
    private boolean mIsDiskLruCacheCreated = false;

    private ImageCompressHelper mCompressHelper;

    /**handler section*/
    private static final int MSG_POST_RESULT = 1000;//
    private Handler mMainHandler = new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            LoadResult loadResult = (LoadResult) msg.obj;
            ImageView imageView = loadResult.getImageView();
            imageView.setImageBitmap(loadResult.getBmp());
//            String uri = imageView.getTag(TAG_KEY_URI);
//            if (uri.equals(loadResult.getUrl())){
//                imageView.setImageBitmap(loadResult.getBmp());
//            }else{
//                Log.d(TAG, "handleMessage: " +
//                        "setImageUri, but uri has changed, ignored!");
//            }

        }
    };

    /**thread pool section*/
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;//核心线程数
    private static final int MAX_POOL_SIZE = CPU_COUNT * 2 + 1;//最大线程数
    private static final long KEEP_ALIVE = 10L;//线程闲置超时时长

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);;
        @Override
        public Thread newThread(@NonNull Runnable runnable) {
            return new Thread(runnable, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    public static final Executor THREAD_POOL_EXECTOR = new ThreadPoolExecutor(CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE,
            TimeUnit.SECONDS,
            new LinkedBlockingDeque<Runnable>(),
            sThreadFactory);


    @SuppressLint("NewApi")
    private ImageLoader(Context context){
        context = context.getApplicationContext();
        mCompressHelper = new ImageCompressHelper();

        //设置最大内存
        int maxMemory = (int)(Runtime.getRuntime().maxMemory()/1024);
        int cacheSize = maxMemory/8;

        //初始化LruCache
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap bmp) {
                return bmp.getRowBytes() * bmp.getHeight() /1024;
            }
        };

        //初始化DiskLruCache
        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!diskCacheDir.exists()){
            diskCacheDir.mkdirs();
        }

        //若磁盘空间足够, 创建DiskLruCache
        if (getUsableDiskSpace(diskCacheDir) > DISK_CACHE_SIZE){
            try {
                mDiskCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }



    }//ImageLoader end

    public static ImageLoader newInstance(Context context){
        return new ImageLoader(context);
    }

    /**
     * 将bitmap添加到LruCache
     * @param key 图片的url经过md5 hash后的结果
     *
     * @param bitmap bitmap
     */
    private void addToMemoryCache(String key, Bitmap bitmap){
        if (mMemoryCache.get(key) == null){
            mMemoryCache.put(key, bitmap);
        }
    }

    /**从LruCache中读取bitmap*/
    private Bitmap getMemoryCache(String key){
        return mMemoryCache.get(key);
    }

    /**从http下载bitmap并缓存*/
    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("can not visit network from UI thread");
        }

        if (mDiskCache == null) return null;

        String key = hashMD5(url);
        //开始将bitmap写入DiskLruCache
        DiskLruCache.Editor editor = mDiskCache.edit(key);
        if (editor != null){
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);

            if (downloadUrlToStream(url, outputStream)){
                editor.commit();
            }else {
                editor.abort();
            }
            mDiskCache.flush();
        }

        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }

    /**从磁盘缓存读取bitmap
     *
     * @param url
     * @param reqWidth
     * @param reqHeight */
    private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight)
    throws IOException{
        if (Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("can not visit network from UI thread");
        }

        if (mDiskCache == null) return null;

        Bitmap bitmap = null;
        String key = hashMD5(url);
        DiskLruCache.Snapshot snapshot = mDiskCache.get(key);
        if (snapshot != null){//读取到了bitmap， resize并返回
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mCompressHelper.decodeSampleBitmapFromFileDescriptor(fileDescriptor,
                    reqWidth,
                    reqHeight);

            if (bitmap != null){
                addToMemoryCache(key, bitmap);
            }
        }
        return bitmap;
    }

    /**从内存缓存读取bitmap
     * @param url
     */
    private Bitmap loadBitmapFromMemCache(String url){
        String key = hashMD5(url);
        return getMemoryCache(key);
    }

    /**http请求, 将返回结果写入到outputStream中*/
    private boolean downloadUrlToStream(String url, OutputStream outputStream) {
        HttpURLConnection httpURLConnection = null;
        BufferedOutputStream bos = null;
        BufferedInputStream bis = null;

        try {
            final URL u = new URL(url);
            httpURLConnection = (HttpURLConnection) u.openConnection();
            bis = new BufferedInputStream(httpURLConnection.getInputStream(), IO_BUFFER_SIZE);
            bos = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

            int b;
            while((b = bis.read()) != -1){
                bos.write(b);
            }
            return true;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if (httpURLConnection != null){
                httpURLConnection.disconnect();
            }
            try {
                bos.close();
                bis.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }


    /**同步方法， 读取bitmap
     * 核心方法.依次从内存缓存 -> 磁盘缓存 -> 网络请求 获取bitmap
     * @param url
     * @param reqWidth 控件需求宽度
     * @param reqHeight 控件需求高度*/
    public Bitmap loadBitmap(String url, int reqWidth, int reqHeight){
        //首先从内存缓存读取bitmap
        Bitmap bitmap = loadBitmapFromMemCache(url);

        if (bitmap != null){
            Log.d(TAG, "loadBitmapFromMemCache: " + url);
            return bitmap;
        }

        //若内存缓存中无缓存, 从磁盘缓存读取
        try {
            bitmap = loadBitmapFromDiskCache(url, reqWidth, reqHeight);
            if (bitmap != null){
                Log.d(TAG, "loadBitmapFromDiskCache: " + url);
                return bitmap;
            }

            //若磁盘缓存中无缓存, 从http读取
            bitmap = loadBitmapFromHttp(url, reqWidth, reqHeight);;
            Log.d(TAG, "loadBitmapFromHttp: " + url);
        } catch (IOException e) {
            e.printStackTrace();
        }

        //错误处理
        if (bitmap == null && !mIsDiskLruCacheCreated){
            bitmap = downloadBitmapFromUrl(url);
        }
        return bitmap;
    }

    /**异步方法, 读取bitmap*/
    public void bindBitmap(final String url,
                             final ImageView imageView,
                             final int reqWidth,
                             final int reqHeight){
        //从内存缓存读取bitmap
        Bitmap bitmap = loadBitmapFromMemCache(url);
        if (bitmap != null){
            imageView.setImageBitmap(bitmap);
            return ;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = loadBitmap(url, reqWidth, reqHeight);
                if (bmp != null){
                    LoadResult loadResult = new LoadResult(imageView, url, bmp);
                    //handler send msg
                    Message message = mMainHandler.obtainMessage(MSG_POST_RESULT, loadResult);
                    mMainHandler.sendMessage(message);
                }
            }
        };

        //execute runnable
        THREAD_POOL_EXECTOR.execute(loadBitmapTask);
    }

    /**从指定url下载bitmap
     * 用于无法从缓存中读取bitmap后的errorHandling*/
    private Bitmap downloadBitmapFromUrl(String url) {
        Bitmap bitmap = null;
        HttpURLConnection httpURLConnection = null;
        BufferedInputStream bis = null;

        try {
            final URL u = new URL(url);
            httpURLConnection = (HttpURLConnection) u.openConnection();
            bis = new BufferedInputStream(httpURLConnection.getInputStream(), IO_BUFFER_SIZE);;
            bitmap = BitmapFactory.decodeStream(bis);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }finally{
            if (httpURLConnection != null){
                httpURLConnection.disconnect();
            }
            try {
                bis.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return bitmap;
    }

    /**MD5 hash算法
     * 将url使用MD5 hash
     *
     * @return 作为diskLruCache 的 key使用*/
    private String hashMD5(String str) {
        String cacheKey;
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance(MD5);
            messageDigest.update(str.getBytes());
            cacheKey = byteToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            cacheKey = String.valueOf(str.hashCode());
        }
        return cacheKey;
    }

    /**
     * 将byte[]转为16进制*/
    private String byteToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<bytes.length; i++){
            String hex = Integer.toHexString(0xff & bytes[i]);
            if (hex.length() == 1){
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    private File getDiskCacheDir(Context context, String uniqueName) {
        boolean externalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable){
            cachePath = context.getExternalCacheDir().getPath();
        }else{
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    private long getUsableDiskSpace(File file){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD){
            return file.getUsableSpace();
        }

        final StatFs stats = new StatFs(file.getPath());
        return stats.getAvailableBlocksLong() *stats.getAvailableBlocksLong();
    }


}
