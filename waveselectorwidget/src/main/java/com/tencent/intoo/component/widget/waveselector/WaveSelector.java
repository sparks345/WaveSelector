package com.tencent.intoo.component.widget.waveselector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Scroller;

/**
 * 波形绘制组件
 * <p>
 * 对应PCM采样点计算：
 * k = 1000 / 44100 * 1024 = 23.21995464852607709750566893424;  // 23.22ms 一个波形点
 * m = 1000 / k = 43.066406250000000000000000000001;  // 每秒43个波形点
 * d = 72 / 40s = 1.8/s;  // 满屏72个绘制点 对应 40s，每秒 1.8个绘制点
 * s = m / d = 23.92578125 ≈ 24;    // 每个点对应24个波形点
 * SAMPLE_STEP * 2
 *
 * @see #calcSampleStep(int, int, int)
 */
public class WaveSelector extends View {
    public static final String TAG = "WaveSelector";

    // TODO 滑动轨道的灵敏度
    private static final int SCROLL_SENSITIVITY = 1;

    private final ArgbEvaluator mArgbEvalutor;

    // 缓存波形数据
    private ArrayList<Volume> mData = new ArrayList<>();
    private int mPlayDuration;

    private float density = getResources().getDisplayMetrics().density;

    // 满屏时长
    private long mFullWidthTrackDuration = 40000;
    // 半屏波形个数
    private int mHalfWaveCount = 36;//36;
    // 满屏波形个数，左右对称，满屏波形数是两个半屏
    private int mWavePageCount = mHalfWaveCount * 2;
    // 波形宽度，通过满屏时长和满屏波形个数换算,默认40s时长72个波形
    // 每个波形单元包括 一个间隔 + 一个波形
    private int mWaveSize = 0;//(int) (density * 10);
    private int mWaveSpace;

    private int mWavePaddingTop = (int) (20 * density);
    private int mWavePaddingBottom = (int) (30 * density);
    private int mFullWidth;
    private int mFullHeight;
    private float mPIX_PER_SECOND;

    /////////////////////////////////////////////////////////
    private VelocityTracker mVelocityTracker;
    private final Scroller mScroll;
    private long mLastPageStart;
    private IWaveSelectorListener mListener = new IWaveSelectorListener() {
        @Override
        public void onChanging(long timeStart) {
            Log.d(TAG, "onChanging() called with: timeStart = [" + timeStart + "]");
        }

        @Override
        public void onSelect(long timeStart) {
            Log.d(TAG, "onSelect() called with: timeStart = [" + timeStart + "]");
        }

        @Override
        public void onReady() {
            Log.d(TAG, "onReady() called");
        }
    };

    /////////////////////////////////////////////////////////
    private float mMaxScrollX;
    private int mCurrentLeft;
    private long mLastScrollEndTS;
    private int mLastCurrX;
    private boolean mIgnoreCallBack;
    // 左右空闲的间距，方便以中点方式选中第0秒和最后一秒
    private int mPaddingPix;

    private SizeConvertAdapter mConvertAdapter;

    private boolean mInited;
    private boolean mIsOnPreDraw;
    private int mAutoSeekTo;

    // 最大滚动起始位置，防止滚出界面
    private int mLastAvailableLeft;

    /////////////////////////////////////////////////////////
    private final RectF mRectVolume = new RectF();
    private final Paint mWavePaint;
    private final Paint mWavePlayingPaint;
    private final Paint mSelectPaint;
//    private final ValueAnimator mValueAnimator;

    // 当前波形渐变色
    private int mCurrentPlayingColor;
    private final AnimatorUpdateListener mColorUpdateListener = new AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mCurrentPlayingColor = (int) animation.getAnimatedValue();
            Log.i(TAG, "color:" + mCurrentPlayingColor);
        }
    };

    /////////////////////////////////////////////////////////
    private static final int SCROLLING_VELOCITY_UNIT = 100;
    private static final float MIN_MOVE_DISTANCE = 20;

    private static final int Direction_UNKNOWN = 0;
    private static final int Direction_RIGHT = 2;
    private static final int Direction_LEFT = 1;

    private boolean mIsDragging;
    private float mLastX;
    private int mDragDirection;

    /////////////////////////////////////////////////////////
    private float mHighLightEndPos;
    private float mHighLightStartPos;
    private float mHighLightProgressPos;
    private boolean isProgressing;
    private Timer timer;
    private TimerTask timerTask;
    private static final int TIMER_TRIGGER = 200;

    /////////////////////////////////////////////////////////
    public WaveSelector(Context context) {
        this(context, null);
    }

    public WaveSelector(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();

        mScroll = new Scroller(getContext());

        mWavePaint = new Paint();// 波形
        mWavePaint.setColor(getResources().getColor(R.color.colorWave));

        mWavePlayingPaint = new Paint();// 播放过的波形
        mWavePlayingPaint.setColor(getResources().getColor(R.color.colorWavePlayed));

        mSelectPaint = new Paint();// 选择线
        mSelectPaint.setColor(getResources().getColor(R.color.colorSelectLine));
        mSelectPaint.setStrokeWidth(2 * density);

        mArgbEvalutor = new ArgbEvaluator();
    }

    private void init() {
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            boolean onPreDrawNotify = true;

            @Override
            public boolean onPreDraw() {
                int testWidth = WaveSelector.this.getWidth();
                if (testWidth <= 0) {
                    if (onPreDrawNotify) {
                        Log.e(TAG, "onPreDraw() not initialized. return.");
                        onPreDrawNotify = false;
                    }
                    return true;
                }
                WaveSelector.this.getViewTreeObserver().removeOnPreDrawListener(this);
                mIsOnPreDraw = true;
                mFullWidth = WaveSelector.this.getWidth();
                mFullHeight = WaveSelector.this.getHeight();
                mPaddingPix = mFullWidth / 2;

                mWaveSize = mFullWidth / mWavePageCount / 2;
                mWaveSpace = mWaveSize;

                mPIX_PER_SECOND = mFullWidth * 1.0f / mFullWidthTrackDuration;
                SizeConvertAdapter.init(mPIX_PER_SECOND);
                mConvertAdapter = SizeConvertAdapter.getInstance();

                Log.d(TAG, "onPreDraw() called mFullWidth:" + mFullWidth + ", mFullHeight:" + mFullHeight);

                callOnReady();

                return true;
            }
        });

    }

    private void callOnReady() {
        if (mInited && mIsOnPreDraw) {
            if (mAutoSeekTo > 0) {
                seekTo(mAutoSeekTo);
            }

            if (mListener != null) {
                mListener.onReady();
            }
        }
    }

    /**
     * 清空数据和状态
     */
    public void dispose() {
        Log.i(TAG, "dispose().");
        clearHighlight();
        mData.clear();
        mInited = false;
        mScroll.setFinalX(0);
        SizeConvertAdapter.dispose();
    }

    /////////////////////////////////////////////////////////


    @Override
    protected void onDraw(Canvas canvas) {
        if (!mInited) return;

        if (mWaveSize <= 0) {
            Log.e(TAG, "skip to. mWaveWidth error." + mWaveSize);
            return;
        }

        if (mMaxScrollX > 0 && !isAvailed(mCurrentLeft)/*x >= mMaxScrollX || x < 0*/) {
            Log.e(TAG, "skip to. mMaxScrollX error." + "x:" + mCurrentLeft + ", max:" + mMaxScrollX);
            mCurrentLeft = mLastAvailableLeft;
        }

        doDraw(canvas);

        super.onDraw(canvas);
    }

    private void doDraw(Canvas canvas) {
        int offSet = (mCurrentLeft) % (mWaveSpace + mWaveSize);
        mLastAvailableLeft = mCurrentLeft;

        ConcurrentLinkedQueue<Volume> careData = getCurrentPageData();
        Iterator<Volume> it = careData.iterator();
        int index = 0;
        while (it.hasNext()) {
            // draw volume.
            Volume dt = it.next();
            float left = index * (mWaveSpace + mWaveSize);
            if (mCurrentLeft <= mPaddingPix) {
                left += (mPaddingPix - mCurrentLeft);
            } else {
                left -= offSet;
            }

            if (left > mFullWidth) break;

            float right = left + mWaveSpace;
            float height = dt.percent * (mFullHeight - mWavePaddingTop - mWavePaddingBottom);
            float top = mFullHeight / 2 - height / 2;
            float bottom = mFullHeight / 2 + height / 2;
            float corner = 2 * density;

            if (left < 0) {
                left = 0;
            }
            mRectVolume.set(left, top, right, bottom);
            if (left >= mHighLightStartPos - mWaveSize && right <= mHighLightProgressPos + mWaveSize) {
                mCurrentPlayingColor = getCurrentWaveColor((left - mHighLightStartPos) / (mHighLightEndPos - mHighLightStartPos));
                mWavePlayingPaint.setColor(mCurrentPlayingColor);
                canvas.drawRoundRect(mRectVolume, corner, corner, mWavePlayingPaint);
            } else {
                canvas.drawRoundRect(mRectVolume, corner, corner, mWavePaint);
            }

            index++;
        }

        // draw select line.
        canvas.drawLine(mFullWidth / 2, 0, mFullWidth / 2, mFullHeight, mSelectPaint);
    }

    private int getCurrentWaveColor(float percent) {
        Log.d(TAG, "getCurrentWaveColor() called with: percent = [" + percent + "]");
        return (int) mArgbEvalutor.evaluate(percent, Color.parseColor("#FF00FF00"), Color.parseColor("#FF0000FF"));
    }

    /////////////////////////////////////////////////////////
    public void startHighLight() {
        if (mPlayDuration <= 0) return;
        int start = mPaddingPix;
        int end = Math.round(start + mConvertAdapter.getPixByTime(mPlayDuration));
        startHighLight(start, end);
    }

    public void startHighLight(float left, float end) {
        Log.w(TAG, "startHighLight. left:" + left + ", end:" + end);
        mHighLightEndPos = end;
        mHighLightStartPos = left;
        mHighLightProgressPos = left;
        if (isProgressing) return;

        Log.i(TAG, "startHighLight......");
        timer = new Timer();
        timerTask = new TimerTask() {
            @Override
            public void run() {
                if (mHighLightProgressPos <= mHighLightEndPos) {
                    mHighLightProgressPos += mConvertAdapter.getPixByTime(TIMER_TRIGGER);//EVALUATOR_STEP;
                } else {
                    // 循环播放启用
                    //mHighLightProgressPos = mHighLightStartPos;

                    // 单次播放启用
                    stopHighlight();
                }

                postInvalidate();
            }
        };

        isProgressing = true;
        timer.schedule(timerTask, 0, TIMER_TRIGGER);

        post(new Runnable() {
            @Override
            public void run() {
//                mValueAnimator.cancel();
//                mValueAnimator.setDuration(mPlayDuration);
//                mValueAnimator.getValues();
//                mValueAnimator.start();
            }
        });
    }

    protected void clearHighlight() {
//        mHighLightEndPos = 0;
//        mHighLightStartPos = 0;
//        mHighLightProgressPos = 0;
        mHighLightProgressPos = mHighLightStartPos;

        stopHighlight();
    }

    private void stopHighlight() {
        isProgressing = false;

        if (this.timerTask != null) {
            this.timerTask.cancel();
            this.timerTask = null;
        }

        if (this.timer != null) {
            this.timer.cancel();
            this.timer = null;
        }

        post(new Runnable() {
            @Override
            public void run() {
//                mValueAnimator.cancel();
            }
        });
    }

    /////////////////////////////////////////////////////////

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Log.v(TAG, "onTouchEvent." + event.getAction() + " x:" + event.getX() + ", y:" + event.getY() + " --> " + mCurrentLeft);

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!mScroll.isFinished()) {
                    mScroll.abortAnimation();
                }
                mLastX = event.getX();
                mIsDragging = true;

                return true;

            case MotionEvent.ACTION_MOVE:
                float moveDiff = event.getX() - mLastX;
                mCurrentLeft -= moveDiff;// 反向滚动
                mLastX = event.getX();
                if (moveDiff > MIN_MOVE_DISTANCE) {
                    mDragDirection = Direction_RIGHT;
                } else if (moveDiff < -MIN_MOVE_DISTANCE) {
                    mDragDirection = Direction_LEFT;
                } else {
                    mDragDirection = Direction_UNKNOWN;
                }
                clearHighlight();
                callbackScrolling();
                break;
            case MotionEvent.ACTION_UP:
                mCurrentLeft -= event.getX() - mLastX;// 反向滚动
                mLastX = event.getX();
                   /*mScroll.startScroll(mScroll.getCurrX(), 0, (int) (mScroll.getCurrX() - mCurrentLeft), 0, 0);
                   mIsDragging = false;*/

                if (mVelocityTracker != null) {
                    mVelocityTracker.computeCurrentVelocity(SCROLLING_VELOCITY_UNIT);
                    int xVelocity = -(int) mVelocityTracker.getXVelocity();// 反向滚动
                    int maxEndX = getMaxEndX();
                    int minStartX = 0;//mLeftPadding;// todo 头部被抹掉了2s
                    mScroll.forceFinished(true);
                    Log.e(TAG, "mCurrentLeft:" + mCurrentLeft + ", xVelocity:" + xVelocity);
                    mScroll.fling((int) mCurrentLeft, 0, xVelocity, 0,
                            minStartX, maxEndX, 0, 0);

                    mVelocityTracker.recycle();
                    mVelocityTracker = null;

                }
//                callbackScroll();
                mIsDragging = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                   /*if (mVelocityTracker != null) {
                       mVelocityTracker.recycle();
                       mVelocityTracker = null;
                       mIsDragging = false;
                   }*/
                callbackScroll();
                mIsDragging = false;
                break;
        }

        postInvalidate();
        Log.v(TAG, "onTouchEvent." + event.getAction() + " x:" + event.getX() + ", y:" + event.getY() + " ==> " + mCurrentLeft);

        return super.onTouchEvent(event);
    }

    protected int getMaxEndX() {
        return Math.round(Math.min(Integer.MAX_VALUE, mData.size() * (mWaveSize + mWaveSpace) + mPaddingPix * 2 /*- mRightPadding*/));
    }

    /**
     * mCurrentLeft will modify after fling.
     *
     * @see #onTouchEvent(MotionEvent) case MotionEvent.ACTION_UP
     */
    @Override
    public void computeScroll() {
//        Log.d(TAG, "computeScroll() called" + mCurrentLeft);

        if (mScroll.computeScrollOffset()) {
            int tmp2 = mScroll.getCurrX();
            if (isAvailed(tmp2)) {
                mCurrentLeft = tmp2 / SCROLL_SENSITIVITY;
            } else {
                mScroll.forceFinished(true);
            }
            postInvalidate();
            mLastScrollEndTS = System.currentTimeMillis();
        } else {
            int tmp = mScroll.getCurrX();
            if (mLastCurrX != tmp) {
                if (mLastScrollEndTS > 0 && System.currentTimeMillis() - mLastScrollEndTS < 100) {
                    Log.w(TAG, "...........fling end......");
                    // fix position after a quick fling.
                    mCurrentLeft = tmp / SCROLL_SENSITIVITY;
                    if (!mIgnoreCallBack) {
                        Log.e(TAG, "ignore current callBack()...");
                        callbackScroll();
                    }

                    mIgnoreCallBack = false;
                }
                mLastCurrX = tmp / SCROLL_SENSITIVITY;
            }
        }
    }

    private void callbackScroll() {
        Log.d(TAG, "callbackScroll() called" + " ... " + mCurrentLeft);
        if (mListener != null && mLastPageStart != mCurrentLeft) {
            mLastPageStart = mCurrentLeft;
            long ts = mConvertAdapter.getTimeByPix(mCurrentLeft);
            mListener.onSelect(ts);
        }
    }

    private void callbackScrolling() {
        Log.d(TAG, "callbackScrolling() called");
        if (mListener != null /*&& mLastPageStart != mCurrentLeft*/) {
//            mLastPageStart = mCurrentLeft;
            long ts = mConvertAdapter.getTimeByPix(mCurrentLeft);
            mListener.onChanging(ts);
        }
    }

    private ConcurrentLinkedQueue<Volume> getCurrentPageData() {
        int index = (mCurrentLeft - mPaddingPix) / (mWaveSize + mWaveSpace);
        Log.e(TAG, "left:" + mCurrentLeft + ", idx:" + index);
        // 当前页的数量
        int pageMax = Math.min(mData.size(), mWavePageCount + 1);
        // 最后一页的开始数据index
        int lastPageIndex = mData.size() - pageMax + (mPaddingPix / (mWaveSize + mWaveSpace));

        if (index < 0) index = 0;
        if (index > lastPageIndex) index = lastPageIndex;

        // 最后一页开始的滚动位置
        mMaxScrollX = (int) ((mWaveSize + mWaveSpace) * lastPageIndex) + mPaddingPix;

        ConcurrentLinkedQueue<Volume> currentPageData = new ConcurrentLinkedQueue<>();
        List<Volume> subList = mData.subList(index, Math.min(index + pageMax, mData.size() - 1));
        currentPageData.addAll(subList);

        return currentPageData;
    }

    boolean isAvailed(float currentX) {
        return currentX <= mMaxScrollX/* - mRightPadding*/ && currentX >= 0;// + mLeftPadding;
    }

    /////////////////////////////////////////////////////////

    /**
     * 设置波形数据
     *
     * @param ll 数据
     */
    public void setData(List<Integer> ll) {
        Log.d(TAG, "setData()");
        if (ll == null) return;

//        Log.v(TAG, "setData() called with: ll = [" + ll.subList(0, Math.min(ll.size(), 10)) + "]..." + ", mInited:" + mInited);
        if (mInited) return;

        mInited = true;
        for (int i = 0; i < ll.size(); i++) {
            mData.add(new Volume(ll.get(i)));
        }

        callOnReady();

        postInvalidate();
    }

    /**
     * 从start位置开始
     *
     * @param start start
     */
    public void seekTo(int start) {
        Log.d(TAG, "seekTo() called with: start = [" + start + "]");
        if (mInited && mIsOnPreDraw) {
            Log.d(TAG, "scrollTo... to:" + start);
            mScroll.setFinalX((int) mConvertAdapter.getPixByTime(start));
        } else {
            mAutoSeekTo = start;
            Log.d(TAG, "seekTo later... to:" + start);
        }
    }

    /**
     * 设置截取时长
     *
     * @param duration 截取时长
     */
    public void setPlayDuration(int duration) {
        Log.d(TAG, "setPlayDuration() called with: duration = [" + duration + "]");
        mPlayDuration = duration;
    }

    /////////////////////////////////////////////////////////
    public interface IWaveSelectorListener {
        void onChanging(long timeStart);

        void onSelect(long timeStart);

        void onReady();
    }

    public void setListener(IWaveSelectorListener listener) {
        this.mListener = listener;
    }

    /////////////////////////////////////////////////////////
    public static class Volume {
        public final int vol;
        private final float percent;

        public Volume(int vol) {
            this.vol = vol;
            this.percent = Math.max(0.05f, vol * 1.0f / 65536);// 最低5%，不要让波形图空着
        }

        @Override
        public String toString() {
            return String.valueOf(vol);
        }
    }

    /////////////////////////////////////////////////////////
    public static class SizeConvertAdapter {
        float mPixPerSec;

        public static SizeConvertAdapter instance;

        public SizeConvertAdapter(float pixPerSec) {
            mPixPerSec = pixPerSec;
        }

        public static void init(float pixPerSec) {
            if (instance != null) throw new IllegalArgumentException("help already initialized.");
            instance = new SizeConvertAdapter(pixPerSec);
        }

        public static void dispose() {
            instance = null;
        }

        private static SizeConvertAdapter getInstance() {
            if (instance == null) throw new IllegalArgumentException("help must init() first.");
            return instance;
        }

        public float getPixByTime(int time) {
            return mPixPerSec * time;
        }

        public long getTimeByPix(long currentLeft) {
            return (long) (currentLeft / mPixPerSec);
        }
    }

    /////////////////////////////////////////////////////////
    public static class Config {
        public int halfWaveCount;
        public int wavePaddingTop;
        public int wavePaddingBottom;
    }

    /////////////////////////////////////////////////////////

    /**
     * 计算采样点密度
     *
     * @param rate               码率，通常为44100
     * @param drawPointCount     满屏绘制点数
     * @param drawDurationSecond 满屏时长
     * @return 采样密度
     */
    public static int calcSampleStep(int rate, int drawPointCount, int drawDurationSecond) {
        float k = 1000.0F / rate * 1024;
        float m = 1000.0F / k;
        float d = 1.0f * drawPointCount / drawDurationSecond;
        float s = m / d;
        return Math.round(s);
    }
}
