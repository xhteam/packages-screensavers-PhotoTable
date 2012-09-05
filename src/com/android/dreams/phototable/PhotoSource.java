/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.dreams.phototable;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Random;

/**
 * Picks a random image from a source of photos.
 */
public abstract class PhotoSource {
    private static final String TAG = "PhotoTable.PhotoSource";
    private static final boolean DEBUG = false;

    // This should be large enough for BitmapFactory to decode the header so
    // that we can mark and reset the input stream to avoid duplicate network i/o
    private static final int BUFFER_SIZE = 128 * 1024;

    public static class ImageData {
        public String id;
        public String url;
        public int orientation;
        public int type;
    }

    private final Context mContext;
    private final LinkedList<ImageData> mImageQueue;
    private final int mMaxQueueSize;

    protected final Resources mResources;
    protected ContentResolver mResolver;
    protected String mSourceName;
    protected final Random mRNG;

    public PhotoSource(Context context) {
        mSourceName = TAG;
        mContext = context;
        mResolver = mContext.getContentResolver();
        mResources = context.getResources();
        mImageQueue = new LinkedList<ImageData>();
        mMaxQueueSize = mResources.getInteger(R.integer.image_queue_size);
        mRNG = new Random();
    }

    protected void fillQueue() {
        log(TAG, "filling queue");
        mImageQueue.addAll(findImages(mMaxQueueSize - mImageQueue.size()));
        Collections.shuffle(mImageQueue);
        log(TAG, "queue contains: " + mImageQueue.size() + " items.");
    }

    public Bitmap next(BitmapFactory.Options options, int longSide, int shortSide) {
        log(TAG, "decoding a picasa resource to " +  longSide + ", " + shortSide);
        Bitmap image = null;

        if (mImageQueue.isEmpty()) {
            fillQueue();
        }

        if (!mImageQueue.isEmpty()) {
            ImageData data = mImageQueue.poll();
            InputStream is = null;
            try {
                is = getStream(data);
                BufferedInputStream bis = new BufferedInputStream(is);
                bis.mark(BUFFER_SIZE);

                options.inJustDecodeBounds = true;
                options.inSampleSize = 1;
                image = BitmapFactory.decodeStream(new BufferedInputStream(bis), null, options);
                int rawLongSide = Math.max(options.outWidth, options.outHeight);
                int rawShortSide = Math.min(options.outWidth, options.outHeight);
                log(TAG, "I see bounds of " +  rawLongSide + ", " + rawShortSide);

                if (rawLongSide != -1 && rawShortSide != -1) {
                    float ratio = Math.max((float) longSide / (float) rawLongSide,
                                           (float) shortSide / (float) rawShortSide);
                    while (ratio < 0.5) {
                        options.inSampleSize *= 2;
                        ratio *= 2;
                    }

                    log(TAG, "decoding with inSampleSize " +  options.inSampleSize);
                    bis.reset();
                    options.inJustDecodeBounds = false;
                    image = BitmapFactory.decodeStream(bis, null, options);
                    rawLongSide = Math.max(options.outWidth, options.outHeight);
                    rawShortSide = Math.max(options.outWidth, options.outHeight);
                    ratio = Math.max((float) longSide / (float) rawLongSide,
                                     (float) shortSide / (float) rawShortSide);

                    if (ratio < 1.0f) {
                        log(TAG, "still too big, scaling down by " + ratio);
                        options.outWidth = (int) (ratio * options.outWidth);
                        options.outHeight = (int) (ratio * options.outHeight);
                        image = Bitmap.createScaledBitmap(image,
                                                          options.outWidth, options.outHeight,
                                                          true);
                    }

                    if (data.orientation != 0) {
                        log(TAG, "rotated by " + data.orientation + ": fixing");
                        if (data.orientation == 90 || data.orientation == 270) {
                            int tmp = options.outWidth;
                            options.outWidth = options.outHeight;
                            options.outHeight = tmp;
                        }
                        Matrix matrix = new Matrix();
                        matrix.setRotate(data.orientation,
                                         (float) image.getWidth() / 2,
                                         (float) image.getHeight() / 2);
                        image = Bitmap.createBitmap(image, 0, 0,
                                                    options.outHeight, options.outWidth,
                                                    matrix, true);
                    }

                    log(TAG, "returning bitmap " + image.getWidth() + ", " + image.getHeight());
                } else {
                    log(TAG, "decoding failed with no error: " + options.mCancel);
                }
            } catch (FileNotFoundException fnf) {
                log(TAG, "file not found: " + fnf);
            } catch (IOException ioe) {
                log(TAG, "i/o exception: " + ioe);
            } finally {
                try {
                    if (is != null) {
                        is.close();
                    }
                } catch (Throwable t) {
                    log(TAG, "close fail: " + t.toString());
                }
            }
        } else {
            log(TAG, mSourceName + " has no images.");
        }

        return image;
    }

    public void setSeed(long seed) {
        mRNG.setSeed(seed);
    }

    protected void log(String tag, String message) {
        if (DEBUG) {
            Log.i(tag, message);
        }
    }

    protected abstract InputStream getStream(ImageData data);
    protected abstract Collection<ImageData> findImages(int howMany);
}