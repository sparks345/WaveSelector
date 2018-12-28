package com.tencent.intoo.component.widget.waveselector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
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

    // 播放Android机型会有选择时间+1、-1来回抖动的现象，尝试优化一下这里
    private boolean mSmoothScrollEnable = true;

    private final ArgbEvaluator mArgbEvaluator;
    // 波形播放段起止颜色
    private final int mPlayingStartColor;
    private final int mPlayingEndColor;

    // 选择线颜色
    private final int mSelectLineColor;
    // 滑动到尾部最少时长限制时选择线的颜色
    private final int mSelectLineOnLimitColor;

    // 缓存波形数据
    private ArrayList<Volume> mData = new ArrayList<>();
    private int mPlayDuration;
    private int mLastDuration = -1;

    private float density = getResources().getDisplayMetrics().density;

    // 满屏时长
    private long mFullWidthTrackDuration = 40000;
    // 半屏波形个数
    private int mHalfWaveCount = 36;//36;
    private int mScrollingVelocity = SCROLLING_VELOCITY_UNIT;
    // 满屏波形个数，左右对称，满屏波形数是两个半屏
    private int mWavePageCount = mHalfWaveCount * 2;
    // 波形宽度，通过满屏时长和满屏波形个数换算,默认40s时长72个波形
    // 每个波形单元包括 一个间隔 + 一个波形
    private float mWaveSize = 0;//(int) (density * 10);
    private float mWaveSpace;

    private int mWavePaddingTop = (int) (20 * density);
    private int mWavePaddingBottom = (int) (30 * density);
    private int mFullWidth;
    private int mFullHeight;
    private float mPIX_PER_SECOND;

    /////////////////////////////////////////////////////////
    private VelocityTracker mVelocityTracker;
    private final Scroller mScroll;
    private long mLastPageStart = -1;//相对pos
    private long mLastScrollingPageStart = -1;//相对pos
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

        @Override
        public void onLimit() {
            Log.d(TAG, "onLimit() called");
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
    private boolean mIsLimiting = false;
    private int mAutoSeekTo;// seek后界面还没ready，先保持在，后续onReady后还原seek

    // 最大滚动起始位置，防止滚出界面
    private int mLastAvailableLeft;

    /////////////////////////////////////////////////////////
    private final RectF mRectVolume = new RectF();
    private final Paint mWavePaint;
    private final Paint mWavePlayingPaint;
    private final Paint mSelectPaint;
//    private final ValueAnimator mValueAnimator;

    /////////////////////////////////////////////////////////
    private static final int SCROLLING_VELOCITY_UNIT = 100;
    private static final float MIN_MOVE_DISTANCE = 1;

    private static final int Direction_UNKNOWN = 0;
    private static final int Direction_RIGHT = 2;
    private static final int Direction_LEFT = 1;

    private boolean mIsDragging;
    private float mLastX;
    private float mLastDownX;
    private int mDragDirection;

    /////////////////////////////////////////////////////////
    private float mHighLightEndPos;//相对位置
    private float mHighLightStartPos;
    private float mHighLightProgressPos;
    private boolean isProgressing;
    private Timer timer;
    private TimerTask timerTask;
    private static final int TIMER_TRIGGER = 200;
    // 最少可选时长
    private int mDefaultLimitSelectTime = 2000;
    // onSelect去重用
    private long mLastCallBackScrollTime;

    /////////////////////////////////////////////////////////
    public WaveSelector(Context context) {
        this(context, null);
    }

    public WaveSelector(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        final Resources res = context.getResources();
        final TypedArray attributes = res.obtainAttributes(attrs, R.styleable.WaveSelector);
        mFullWidthTrackDuration = attributes.getInt(R.styleable.WaveSelector_full_width_track_duration, (int) mFullWidthTrackDuration);
        mHalfWaveCount = attributes.getInt(R.styleable.WaveSelector_half_wave_count, mHalfWaveCount);
        mScrollingVelocity = attributes.getInt(R.styleable.WaveSelector_scrolling_velocity, SCROLLING_VELOCITY_UNIT);

        init();

        mScroll = new Scroller(getContext());

        mWavePaint = new Paint();// 波形
        int waveColor = attributes.getColor(R.styleable.WaveSelector_wave_color, getResources().getColor(R.color.colorWave));
        mWavePaint.setColor(waveColor);

        mWavePlayingPaint = new Paint();// 播放过的波形
        mWavePlayingPaint.setColor(getResources().getColor(R.color.colorWavePlayed));

        mSelectPaint = new Paint();// 选择线
        mSelectLineColor = attributes.getColor(R.styleable.WaveSelector_wave_select_line_color, getResources().getColor(R.color.colorSelectLine));
        mSelectLineOnLimitColor = attributes.getColor(R.styleable.WaveSelector_wave_select_line_on_limit_color, getResources().getColor(R.color.colorSelectLine));
        mSelectPaint.setColor(mSelectLineColor);
        mSelectPaint.setStrokeWidth(2 * density);

        mArgbEvaluator = new ArgbEvaluator();

        // color.
        mPlayingStartColor = attributes.getColor(R.styleable.WaveSelector_wave_playing_color_start_color, getResources().getColor(R.color.colorWavePlayed));
        mPlayingEndColor = attributes.getColor(R.styleable.WaveSelector_wave_playing_color_end_color, getResources().getColor(R.color.colorWavePlayed));

        // padding.
        mWavePaddingTop = (int) attributes.getDimension(R.styleable.WaveSelector_wave_padding_top, 20 * density);
        mWavePaddingBottom = (int) attributes.getDimension(R.styleable.WaveSelector_wave_padding_bottom, 30 * density);

        attributes.recycle();
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

                mWaveSize = mFullWidth * 1.0f / mWavePageCount / 2;
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
            post(new Runnable() {
                @Override
                public void run() {
                    if (mAutoSeekTo > 0) {
                        seekTo(mAutoSeekTo);
                    }

                    if (mListener != null) {
                        mListener.onReady();
                    }
                }
            });
        }
    }

    private void callOnLimit() {
        Log.v(TAG, "callOnLimit() called");
        mIsLimiting = true;
        if (mInited && mIsOnPreDraw) {
            if (mListener != null) {
                mListener.onLimit();
            }
        }
    }

    /**
     * 清空数据和状态
     */
    public void dispose() {
        Log.i(TAG, "dispose().");
        clearHighLight();
        mData.clear();
        mInited = false;
//        mScroll.setFinalX(0);
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
        int offSet = (int) ((mCurrentLeft - mPaddingPix) % (mWaveSpace + mWaveSize));
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
                // 当前波形条渐变色
                int mCurrentPlayingColor = getCurrentWaveColor((left - mHighLightStartPos) / (mHighLightEndPos - mHighLightStartPos));
                mWavePlayingPaint.setColor(mCurrentPlayingColor);
                canvas.drawRoundRect(mRectVolume, corner, corner, mWavePlayingPaint);
            } else {
                canvas.drawRoundRect(mRectVolume, corner, corner, mWavePaint);
            }

            index++;
        }

        // draw select line.
        mSelectPaint.setColor(mIsLimiting ? mSelectLineOnLimitColor : mSelectLineColor);
        canvas.drawLine(mFullWidth / 2, 0, mFullWidth / 2, mFullHeight, mSelectPaint);
    }

    /**
     * 通过起止色计算当前要绘制的波形色
     *
     * @param percent 进度百分比
     * @return 颜色
     */
    private int getCurrentWaveColor(float percent) {
//        Log.d(TAG, "getCurrentWaveColor() called with: percent = [" + percent + "]");
        return (int) mArgbEvaluator.evaluate(percent, mPlayingStartColor, mPlayingEndColor);
    }

    /////////////////////////////////////////////////////////
    public void startHighLight() {
        if (!mInited) return;
        if (mPlayDuration <= 0) return;
        int start = mPaddingPix;
        int end = Math.round(start + mConvertAdapter.getPixByTime(mPlayDuration));
        startHighLight(start, end);
    }

    public void startHighLight(float left, float end) {
        Log.w(TAG, "startHighLight. left:" + left + ", end:" + end);
        if (!mInited) return;
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
                    stopHighLight();
                }

                postInvalidate();
            }
        };

        isProgressing = true;
        timer.schedule(timerTask, 0, TIMER_TRIGGER);
    }

    public void resumeHighLight() {
        if (!mInited) return;
        Log.d(TAG, "resumeHighLight() called");
        float current = mHighLightProgressPos - mPaddingPix;
        startHighLight();
        seekHighLight(current);
    }

    private void seekHighLight(float seekPos) {
        if (!mInited) return;
        seekPos += mPaddingPix;
        Log.i(TAG, "seekHighLight() ... pos:" + seekPos);
        if (seekPos + 50 >= mHighLightStartPos && seekPos - 50 <= mHighLightEndPos) {// 50为允许的误差值，eg left720.0, start719.992 ...
            mHighLightProgressPos = seekPos;
            Log.v(TAG, "................" + mHighLightProgressPos);
            postInvalidate();
        }
    }

    public void seekHighLightToTime(int ts) {
        if (!mInited) return;
        Log.d(TAG, "seekHighLightToTime() called with: ts = [" + ts + "]");
        seekHighLight(mConvertAdapter.getPixByTime(ts) - mLastPageStart);
    }

    protected void clearHighLight() {
//        mHighLightEndPos = 0;
//        mHighLightStartPos = 0;
//        mHighLightProgressPos = 0;
        mHighLightProgressPos = mHighLightStartPos;

        stopHighLight();
    }

    public void stopHighLight() {
        isProgressing = false;

        if (this.timerTask != null) {
            this.timerTask.cancel();
            this.timerTask = null;
        }

        if (this.timer != null) {
            this.timer.cancel();
            this.timer = null;
        }

//        post(new Runnable() {
//            @Override
//            public void run() {
////                mValueAnimator.cancel();
//            }
//        });
    }

    /////////////////////////////////////////////////////////

    @Override
    public boolean onTouchEvent(MotionEvent event) {
//        Log.v(TAG, "onTouchEvent." + event.getAction() + " x:" + event.getX() + ", y:" + event.getY() + " --> " + mCurrentLeft);

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
                mLastDownX = mLastX;
                mIsDragging = true;

                return true;

            case MotionEvent.ACTION_MOVE:
                float moveDiff = event.getX() - mLastX;
                mCurrentLeft -= moveDiff;// 反向滚动
                mLastX = event.getX();
                if (moveDiff >= MIN_MOVE_DISTANCE) {
                    mDragDirection = Direction_RIGHT;
                } else if (moveDiff < -MIN_MOVE_DISTANCE) {
                    mDragDirection = Direction_LEFT;
                } else {
                    mDragDirection = Direction_UNKNOWN;
                }

                if (Math.abs(mLastX - mLastDownX) > MIN_MOVE_DISTANCE/* || mCurrentLeft <= MIN_MOVE_DISTANCE*/) {
                    clearHighLight();
                    callbackScrolling();
                }

//                if (!isAvailed(mCurrentLeft)) {
                if (mMaxScrollX > 0 && mCurrentLeft > mMaxScrollX) {
                    callOnLimit();
                }

                break;
            case MotionEvent.ACTION_UP:
                mCurrentLeft -= event.getX() - mLastX;// 反向滚动
                mLastX = event.getX();
                   /*mScroll.startScroll(mScroll.getCurrX(), 0, (int) (mScroll.getCurrX() - mCurrentLeft), 0, 0);
                   mIsDragging = false;*/

                if (mVelocityTracker != null) {
                    mVelocityTracker.computeCurrentVelocity(mScrollingVelocity);
                    int xVelocity = -(int) mVelocityTracker.getXVelocity();// 反向滚动
                    int maxEndX = getMaxEndX();
                    int minStartX = 0;//mLeftPadding;// todo 头部被抹掉了2s, 181117 <--看不懂了，2s这是啥?
                    mScroll.forceFinished(true);
                    Log.e(TAG, "mCurrentLeft:" + mCurrentLeft + ", xVelocity:" + xVelocity);
                    mScroll.fling((int) mCurrentLeft, 0, xVelocity, 0,
                            minStartX, maxEndX, 0, 0);

                    mVelocityTracker.recycle();
                    mVelocityTracker = null;

                }
//                callbackScroll();
                if (mCurrentLeft <= MIN_MOVE_DISTANCE) {
//                    callbackScroll();// 这里首次会误触一次0
                }
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

        mCurrentLeft = Math.max(0, mCurrentLeft);
        postInvalidate();
//        Log.v(TAG, "onTouchEvent." + event.getAction() + " x:" + event.getX() + ", y:" + event.getY() + " ==> " + mCurrentLeft);

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

            if (mLastCurrX != tmp2) {
                callbackScrolling();
            }
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
        mIsLimiting = false;
        if (mListener != null && (mLastPageStart != mCurrentLeft || System.currentTimeMillis() - mLastCallBackScrollTime > 200) || mCurrentLeft == 0) {// 0最左端，也需要触发onSelect，这里hack一下吧。。
            mLastPageStart = mCurrentLeft;
            mLastScrollingPageStart = mCurrentLeft;
            long ts = mConvertAdapter.getTimeByPix(mCurrentLeft);
            mListener.onSelect(ts);
            mLastCallBackScrollTime = System.currentTimeMillis();
        }
    }

    private void callbackScrolling() {
//        Log.v(TAG, "callbackScrolling() called");
        if (mListener != null /*&& mLastPageStart != mCurrentLeft*/ && smoothScrollValid()) {
//            mLastPageStart = mCurrentLeft;
            long ts = mConvertAdapter.getTimeByPix(mCurrentLeft);
            mListener.onChanging(ts);
        }
    }

    private boolean smoothScrollValid() {
        boolean ret = false;
        if (mSmoothScrollEnable) {
//            long tsLast = mConvertAdapter.getTimeByPix(mLastScrollingPageStart);
//            long tsNow = mConvertAdapter.getTimeByPix(mCurrentLeft);
//            Log.v(TAG, "N:" + tsNow + ", L:" + tsLast);
            if (mDragDirection == Direction_LEFT) {
                ret = mCurrentLeft >= mLastScrollingPageStart;
            } else if (mDragDirection == Direction_RIGHT) {
                ret = mCurrentLeft <= mLastScrollingPageStart;
            } else {
//                ret = true;
            }
        }

        mLastScrollingPageStart = mCurrentLeft;

        return ret;
    }

    private ConcurrentLinkedQueue<Volume> getCurrentPageData() {
        int index = (int) ((mCurrentLeft - mPaddingPix) / (mWaveSize + mWaveSpace));
//        Log.v(TAG, "left:" + mCurrentLeft + ", idx:" + index);
        // 当前页的数量
        int pageMax = Math.min(mData.size(), mWavePageCount + 1);
        // 最后一页的开始数据index
        int lastPageIndex = (int) (mData.size() - pageMax + (mPaddingPix / (mWaveSize + mWaveSpace)));

        if (index < 0) index = 0;
        if (index > lastPageIndex) index = lastPageIndex;

        // 最后一页开始的滚动位置
        mMaxScrollX = (int) ((mWaveSize + mWaveSpace) * lastPageIndex) + mPaddingPix;
        mMaxScrollX = Math.max(0, mMaxScrollX - mConvertAdapter.getPixByTime(mDefaultLimitSelectTime));

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
        if (mInited) {
            Log.e(TAG, "setData(). already inited. ignore...");
            return;
        }

        mInited = true;
        for (int i = 0; i < ll.size(); i++) {
            mData.add(new Volume(ll.get(i)));
        }

        invalidate();

        callOnReady();

    }

    /**
     * 刷新波形的数据，不触发各种回调
     *
     * @param ll 数据
     */
    public void refreshData(List<Integer> ll) {
        Log.d(TAG, "refreshData() called with: ll = [" + ll + "]");
        if (ll == null) return;

        if (!mInited) {
            Log.e(TAG, "refreshData(). not inited. ignore...");
            return;
        }

        mData.clear();
        for (int i = 0; i < ll.size(); i++) {
            mData.add(new Volume(ll.get(i)));
        }

        invalidate();
    }

    /**
     * 从start位置开始
     *
     * @param start start
     */
    public void seekTo(int start) {
        Log.d(TAG, "seekTo() called with: start time = [" + start + "] , from pos = [" + mCurrentLeft + "]");
        if (mInited && mIsOnPreDraw) {
            Log.d(TAG, "scrollTo... to:" + start);

//            mCurrentLeft =  mScroll.getFinalX();
//            mScroll.fling((int) mCurrentLeft, 0, 11, 0,
//                    0, getMaxEndX(), 0, 0);
//            mScroll.startScroll(mCurrentLeft, 0, (int) mConvertAdapter.getPixByTime(start) - mCurrentLeft, 0, 0);
//            MotionEvent.obtain(0, System.currentTimeMillis(), MotionEvent.ACTION_SCROLL,
//                           1, pointerProperties, pointerCoords, 0, 0, xPrecision, yPrecision, deviceId,
//                           edgeFlags, inputDevice, flags);
//               }
            if (mVelocityTracker == null) {
                setSimulateClick(this, 0, 0);
            }

            int lastCurrentX = mCurrentLeft;
            int newCurrentX = Math.round(mConvertAdapter.getPixByTime(start));

            mScroll.setFinalX(newCurrentX);
            if (lastCurrentX == newCurrentX) {// 这种两次相同的场景得强制触发一次callbackScroll
                callbackScroll();
            }
        } else {
            mAutoSeekTo = start;
            Log.d(TAG, "seekTo later... to:" + start);
        }
    }

    /**
     * hack 方法，模拟一次点击，防止首次seek无效
     *
     * @param view v
     * @param x    x
     * @param y    y
     */
    private void setSimulateClick(View view, float x, float y) {
        Log.d(TAG, "setSimulateClick() called with: view = [" + view + "], x = [" + x + "], y = [" + y + "]");
        long downTime = SystemClock.uptimeMillis();
        final MotionEvent downEvent = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, x, y, 0);
        downTime += 1;
        final MotionEvent upEvent = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(downEvent);
        view.onTouchEvent(upEvent);
        downEvent.recycle();
        upEvent.recycle();
    }


    /**
     * 设置截取时长
     *
     * @param duration 截取时长
     */
    public void setPlayDuration(int duration) {
        boolean showLog = duration != mLastDuration;
        if (showLog) {
            Log.d(TAG, "setPlayDuration() called with: duration = [" + duration + "]");
        }
        mLastDuration = duration;
        mPlayDuration = duration;
        if (isProgressing) {
            int start = mPaddingPix;
            mHighLightEndPos = Math.round(start + mConvertAdapter.getPixByTime(mPlayDuration));
            if (showLog) {
                Log.w(TAG, "setPlayDuration, already progressing, refresh mHighLightEndPos:" + mHighLightEndPos);
            }
        }
    }

    public int getPlayDuration() {
        return mDragDirection;
    }

    /**
     * 最少选择时间，波形滑动值最小时长时会停住
     *
     * @param timeSpan 时长
     */
    public void setLimitedSelectTime(int timeSpan) {
        Log.d(TAG, "setLimitedSelectTime: " + timeSpan);
        mDefaultLimitSelectTime = timeSpan;
    }

    /////////////////////////////////////////////////////////
    public interface IWaveSelectorListener {
        void onChanging(long timeStart);

        void onSelect(long timeStart);

        void onReady();

        void onLimit();
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
            if (instance != null) Log.e(TAG, "help already initialized.");
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
