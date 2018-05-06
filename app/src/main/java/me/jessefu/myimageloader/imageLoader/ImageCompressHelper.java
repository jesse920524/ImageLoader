package me.jessefu.myimageloader.imageLoader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.FileDescriptor;

/**
 * Created by Jesse Fu on 2018-04-10.
 *
 * ImageeCompreHelper类提供了图片压缩功能
 */

public class ImageCompressHelper {
    private static final String TAG = "ImageCompressHelper";

    public ImageCompressHelper(){

    }

    /**
     * 从resource中解析图片
     *
     * @param res getResources()
     * @param resId 资源id
     * @param reqWidth 需求宽度
     * @param reqHeight 需求高度
     *
     * 该方法分为4个步骤:
     * 1.加载图片;
     * 2.从BitmapFactory.Options中取出图片的原始w/h信息
     * 3.根据采样率规则,结合目标view所需大小计算出采样率
     * 4.重新加载图片*/
    public Bitmap decodeSampledBitmapFromReource(Resources res, int resId, int reqWidth, int reqHeight){
        //首先解析res 得到options
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options);

        //根据options.outWidth, options.outHeight, reqWidth, reqHeight 计算采样率
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        //设置采样率后,重新解码图片
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res, resId, options);
    }

    /**从file解析图片并压缩*/
    public Bitmap decodeSampleBitmapFromFileDescriptor(FileDescriptor fd, int reqWidth, int reqHeight){
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd, null, options);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        options.inJustDecodeBounds = true;
        return BitmapFactory.decodeFileDescriptor(fd, null, options);
    }


    /**根据图片原始w/h, 目标view需求w/h, 计算采样率*/
    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {

        if (reqWidth == 0 || reqHeight == 0){
            return 1;
        }

        //得到options中图片的原始w/h
        int outWidth = options.outWidth;
        int outHeight = options.outHeight;
        int inSampleSize = 1;
        Log.d(TAG, "calculateInSampleSize: origin w: " + outWidth + " h: " + outHeight);

        //调整采样率
        if (outWidth > reqWidth || outHeight > reqHeight){
            final int halfWidth = outWidth/2;
            final int halfHeight = outHeight/2;

            while (halfWidth/inSampleSize >= reqWidth &&
                    halfHeight/inSampleSize >= reqHeight){
                inSampleSize *= 2;
            }
        }
        Log.d(TAG, "calculateInSampleSize: result inSampleSize: " + inSampleSize);
        return inSampleSize;
    }
}
