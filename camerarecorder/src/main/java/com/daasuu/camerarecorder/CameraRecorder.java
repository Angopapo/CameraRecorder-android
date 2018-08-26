package com.daasuu.camerarecorder;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraManager;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.daasuu.camerarecorder.capture.EncodeRenderHandler;
import com.daasuu.camerarecorder.egl.GlPreviewRenderer;
import com.daasuu.camerarecorder.egl.filter.GlFilter;

/**
 * Created by sudamasayuki on 2018/03/14.
 */

public class CameraRecorder {
    private GlPreviewRenderer glPreviewRenderer;

    private final CameraRecordListener cameraRecordListener;
    private static final String TAG = "CameraRecorder";

    private boolean started = false;
    private CameraHandler cameraHandler = null;
    private GLSurfaceView glSurfaceView;

    private boolean flashSupport = false;

    //    private MediaMuxerCaptureWrapper muxer;
    private MediaRecorder mediaRecorder;
    private EncodeRenderHandler encodeRenderHandler;

    private final int fileWidth;
    private final int fileHeight;

    private final int cameraWidth;
    private final int cameraHeight;
    private final LensFacing lensFacing;
    private final boolean flipHorizontal;
    private final boolean flipVertical;
    private final boolean mute;
    private final CameraManager cameraManager;
    private final boolean isLandscapeDevice;
    private final int degrees;
    private final boolean recordNoFilter;
    private String filepath;
    private Surface recordSurface;

    CameraRecorder(
            CameraRecordListener cameraRecordListener,
            final GLSurfaceView glSurfaceView,
            final int fileWidth,
            final int fileHeight,
            final int cameraWidth,
            final int cameraHeight,
            final LensFacing lensFacing,
            final boolean flipHorizontal,
            final boolean flipVertical,
            final boolean mute,
            final CameraManager cameraManager,
            final boolean isLandscapeDevice,
            final int degrees,
            final boolean recordNoFilter,
            String filePath
    ) {


        this.cameraRecordListener = cameraRecordListener;

        glSurfaceView.setDebugFlags(GLSurfaceView.DEBUG_CHECK_GL_ERROR);
        this.glSurfaceView = glSurfaceView;

        this.fileWidth = fileWidth;
        this.fileHeight = fileHeight;
        this.cameraWidth = cameraWidth;
        this.cameraHeight = cameraHeight;
        this.lensFacing = lensFacing;
        this.flipHorizontal = flipHorizontal;
        this.flipVertical = flipVertical;
        this.mute = mute;
        this.cameraManager = cameraManager;
        this.isLandscapeDevice = isLandscapeDevice;
        this.degrees = degrees;
        this.recordNoFilter = recordNoFilter;

        this.filepath = filePath;
        // create preview Renderer
        if (null == glPreviewRenderer) {
            glPreviewRenderer = new GlPreviewRenderer(glSurfaceView);
        }

        glPreviewRenderer.setSurfaceCreateListener(new GlPreviewRenderer.SurfaceCreateListener() {
            @Override
            public void onCreated(SurfaceTexture surfaceTexture) {
                startPreview(surfaceTexture);
            }
        });
        setUpRecorder();
    }


    private synchronized void startPreview(final SurfaceTexture surfaceTexture) {
        if (cameraHandler == null) {
            final CameraThread thread = new CameraThread(cameraRecordListener, new CameraThread.OnStartPreviewListener() {
                @Override
                public void onStart(Size previewSize, boolean flash) {

                    Log.d(TAG, "previewSize : width " + previewSize.getWidth() + " height = " + previewSize.getHeight());
                    if (glPreviewRenderer != null) {
                        glPreviewRenderer.setCameraResolution(new Resolution(previewSize.getWidth(), previewSize.getHeight()));
                    }

                    flashSupport = flash;
                    if (cameraRecordListener != null) {
                        cameraRecordListener.onGetFlashSupport(flashSupport);
                    }

                    final float previewWidth = previewSize.getWidth();
                    final float previewHeight = previewSize.getHeight();

                    glSurfaceView.post(new Runnable() {
                        @Override
                        public void run() {
                            if (glPreviewRenderer != null) {
                                glPreviewRenderer.setAngle(degrees);
                                glPreviewRenderer.onStartPreview(previewWidth, previewHeight, isLandscapeDevice);
                            }
                        }
                    });

                    if (glPreviewRenderer != null) {
                        final SurfaceTexture st = glPreviewRenderer.getPreviewTexture().getSurfaceTexture();
                        st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                    }
                }
            }, surfaceTexture, cameraManager, lensFacing);
            thread.start();
            cameraHandler = thread.getHandler();
        }
        cameraHandler.startPreview(cameraWidth, cameraHeight);
        setUpRecorder();
    }


    public void setFilter(final GlFilter filter) {
        if (filter == null) return;
        glPreviewRenderer.setGlFilter(filter);
        releaseMediaRecorder();
        releaseRecordHandler();
        setUpRecorder();
    }

    /**
     * change focus
     */
    public void changeManualFocusPoint(float eventX, float eventY, int viewWidth, int viewHeight) {
        if (cameraHandler != null) {
            cameraHandler.changeManualFocusPoint(eventX, eventY, viewWidth, viewHeight);
        }
    }

    public void changeAutoFocus() {
        if (cameraHandler != null) {
            cameraHandler.changeAutoFocus();
        }
    }


    public void switchFlashMode() {
        if (!flashSupport) return;
        if (cameraHandler != null) {
            cameraHandler.switchFlashMode();
        }
    }

    public void setGestureScale(float scale) {
        if (glPreviewRenderer != null) {
            glPreviewRenderer.setGestureScale(scale);
        }
    }

    public void setFilepath(String filepath) {
        this.filepath = filepath;
        setUpRecorder();
    }

    public boolean isFlashSupport() {
        return flashSupport;
    }


    private void destroyPreview() {
        if (glPreviewRenderer != null) {
            glPreviewRenderer.release();
            glPreviewRenderer = null;
        }
        if (cameraHandler != null) {
            // just request stop prviewing
            cameraHandler.stopPreview(false);
        }
    }

    private void setUpMediaRecorder(Surface surface) {
        try {
            mediaRecorder = new MediaRecorder();

            mediaRecorder.setOnInfoListener(null);

            mediaRecorder.setOnErrorListener(null);

            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            if (!mute) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if (!mute) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioSamplingRate(44100);
                mediaRecorder.setAudioEncodingBitRate(96000);
            }

            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.DEFAULT);

            mediaRecorder.setVideoEncodingBitRate(12000000);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoSize(fileWidth, fileHeight);
            mediaRecorder.setOutputFile(filepath);
            mediaRecorder.setInputSurface(surface);
            mediaRecorder.prepare();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setUpRecorder() {
        new Handler().post(new Runnable() {
            @Override
            public void run() {

                recordSurface = MediaCodec.createPersistentInputSurface();
                setUpMediaRecorder(recordSurface);

                float viewWidth = glSurfaceView.getMeasuredWidth();
                float viewHeight = glSurfaceView.getMeasuredHeight();
                encodeRenderHandler = EncodeRenderHandler.createHandler(
                        TAG,
                        flipVertical,
                        flipHorizontal,
                        (viewWidth > viewHeight) ? (viewWidth / viewHeight) : (viewHeight / viewWidth),
                        fileWidth,
                        fileHeight,
                        recordNoFilter,
                        glPreviewRenderer.getFilter()
                );

                glPreviewRenderer.setEncodeRenderHandler(encodeRenderHandler, recordSurface);
            }
        });
    }


    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
                mediaRecorder = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void releaseRecordHandler() {
        if (encodeRenderHandler != null) {
            encodeRenderHandler.release();
            encodeRenderHandler = null;
            glPreviewRenderer.setEncodeRenderHandler(null, null);
        }
        if (recordSurface != null) {
            recordSurface.release();
            recordSurface = null;
        }
    }

    /**
     * Start data processing
     */
    public void start() {
        if (started) return;
        if (filepath == null) {
            throw new NullPointerException("nothing output file path");
        }
        new Handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    mediaRecorder.start();
                } catch (Exception e) {
                    notifyOnError(e);
                    releaseMediaRecorder();
                }
            }
        });
        started = true;
    }

    /**
     * Stops capturing.
     */
    public void stop() {
        if (!started) return;
        glPreviewRenderer.setEncodeRenderHandler(null, null);
        try {
            mediaRecorder.stop();
        } catch (Exception e) {
            notifyOnError(e);
            e.printStackTrace();
        } finally {
            releaseMediaRecorder();
            releaseRecordHandler();
            this.filepath = null;
        }
        notifyOnDone();
        started = false;
    }

    public void release() {
        // destroy everithing
        try {
            if (recordSurface != null) {
                recordSurface.release();
            }
            releaseMediaRecorder();
        } catch (Exception e) {
            // RuntimeException is thrown when stop() is called immediately after start().
            // In this case the output file is not properly constructed ans should be deleted.
            Log.d("TAG", "RuntimeException: stop() is called immediately after start()");
        }
        destroyPreview();
    }


    public boolean isStarted() {
        return started;
    }

    private void notifyOnDone() {
        if (cameraRecordListener == null) return;
        cameraRecordListener.onRecordComplete();
    }

    private void notifyOnError(Exception e) {
        if (cameraRecordListener == null) return;
        cameraRecordListener.onError(e);
    }


}

