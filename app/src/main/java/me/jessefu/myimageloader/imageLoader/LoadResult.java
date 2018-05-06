package me.jessefu.myimageloader.imageLoader;

import android.graphics.Bitmap;
import android.widget.ImageView;

/**
 * Created by Jesse Fu on 2018-04-22.
 */

public final class LoadResult {

    private ImageView imageView;
    private String url;
    private Bitmap bmp;

    public LoadResult(ImageView imageView, String url, Bitmap bmp) {
        this.imageView = imageView;
        this.url = url;
        this.bmp = bmp;
    }

    public ImageView getImageView() {
        return imageView;
    }

    public String getUrl() {
        return url;
    }

    public Bitmap getBmp() {
        return bmp;
    }

    @Override
    public String toString() {
        return "LoadResult{" +
                "imageView=" + imageView +
                ", url='" + url + '\'' +
                ", bmp=" + bmp +
                '}';
    }
}
