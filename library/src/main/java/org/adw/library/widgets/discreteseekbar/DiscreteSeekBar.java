/*
 * Copyright (c) Gustavo Claramunt (AnderWeb) 2014.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.adw.library.widgets.discreteseekbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.Nullable;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import org.adw.library.widgets.discreteseekbar.internal.PopupIndicator;
import org.adw.library.widgets.discreteseekbar.internal.compat.AnimatorCompat;
import org.adw.library.widgets.discreteseekbar.internal.compat.SeekBarCompat;
import org.adw.library.widgets.discreteseekbar.internal.drawable.MarkerDrawable;
import org.adw.library.widgets.discreteseekbar.internal.drawable.ThumbDrawable;
import org.adw.library.widgets.discreteseekbar.internal.drawable.TrackRectDrawable;

import java.util.Formatter;
import java.util.Locale;

public class DiscreteSeekBar extends View {

    /**
     * Interface to propagate seekbar change event
     */
    public interface OnProgressChangeListener {
        /**
         * When the {@link DiscreteSeekBar} value changes
         *
         * @param seekBar  The DiscreteSeekBar
         * @param value    the new value
         * @param fromUser if the change was made from the user or not (i.e. the developer calling {@link #setProgress(int)}
         */
        public void onProgressChanged(DiscreteSeekBar seekBar, int value, boolean fromUser);
    }

    /**
     * Interface to transform the current internal value of this DiscreteSeekBar to anther one for the visualization.
     * <p/>
     * This will be used on the floating bubble to diaplay a different value if needed.
     * <p/>
     * Using this in conjunction with {@link #setIndicatorFormatter(String)} you will be able to manipulate the
     * value seen by the user
     *
     * @see #setIndicatorFormatter(String)
     * @see #setNumericTransformer(DiscreteSeekBar.NumericTransformer)
     */
    public interface NumericTransformer {
        /**
         * Return the desired value to be shown to the user.
         *
         * @param value
         * @return
         */
        public int transform(int value);
    }

    private static class DefaulNumericTransformer implements NumericTransformer {

        @Override
        public int transform(int value) {
            return value;
        }
    }


    private static final boolean isLollipopOrGreater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    //We want to always use a formatter so the indicator numbers are "translated" to specific locales.
    private static final String DEFAULT_FORMATTER = "%d";

    private static final int PRESSED_STATE = android.R.attr.state_pressed;
    private static final int FOCUSED_STATE = android.R.attr.state_focused;
    private static final int PROGRESS_ANIMATION_DURATION = 250;
    private ThumbDrawable mThumb;
    private Drawable mTrack;
    private Drawable mScrubber;
    private Drawable mRipple;

    private int mTrackHeight;
    private int mScrubberHeight;
    private int mAddedTouchBounds;

    private int mMax;
    private int mMin;
    private int mValue;
    private int mKeyProgressIncrement = 1;
    private boolean mMirrorForRtl = false;
    //We use our own Formatter to avoid creating new instances on every progress change
    Formatter mFormatter;
    private String mIndicatorFormatter;
    private NumericTransformer mNumericTransformer;
    private OnProgressChangeListener mPublicChangeListener;
    private boolean mIsDragging;
    private int mDraggOffset;

    private Rect mInvalidateRect = new Rect();
    private Rect mTempRect = new Rect();
    private PopupIndicator mIndicator;
    private AnimatorCompat mPositionAnimator;
    private float mAnimationPosition;
    private int mAnimationTarget;

    public DiscreteSeekBar(Context context) {
        this(context, null);
    }

    public DiscreteSeekBar(Context context, AttributeSet attrs) {
        this(context, attrs, R.style.DefaultSeekBar);
    }

    public DiscreteSeekBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setFocusable(true);
        setWillNotDraw(false);

        float density = context.getResources().getDisplayMetrics().density;
        mTrackHeight = (int) (1 * density);
        mScrubberHeight = (int) (4 * density);
        int thumbSize = (int) (density * ThumbDrawable.DEFAULT_SIZE_DP);

        //Extra pixels for a touch area of 48dp
        int touchBounds = (int) (density * 32);
        mAddedTouchBounds = (touchBounds - thumbSize) / 2;


        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.DiscreteSeekBar,
                R.attr.discreteSeekBarStyle, defStyle);

        int max = 100;
        int min = 0;
        int value = 0;
        mMirrorForRtl = a.getBoolean(R.styleable.DiscreteSeekBar_dsb_mirrorForRtl, mMirrorForRtl);

        int indexMax = R.styleable.DiscreteSeekBar_dsb_max;
        int indexMin = R.styleable.DiscreteSeekBar_dsb_min;
        int indexValue = R.styleable.DiscreteSeekBar_dsb_value;
        final TypedValue out = new TypedValue();
        //Not sure why, but we wanted to be able to use dimensions here...
        if (a.getValue(indexMax, out)) {
            if (out.type == TypedValue.TYPE_DIMENSION) {
                max = a.getDimensionPixelSize(indexMax, max);
            } else {
                max = a.getInteger(indexMax, max);
            }
        }
        if (a.getValue(indexMin, out)) {
            if (out.type == TypedValue.TYPE_DIMENSION) {
                min = a.getDimensionPixelSize(indexMin, min);
            } else {
                min = a.getInteger(indexMin, min);
            }
        }
        if (a.getValue(indexValue, out)) {
            if (out.type == TypedValue.TYPE_DIMENSION) {
                value = a.getDimensionPixelSize(indexValue, value);
            } else {
                value = a.getInteger(indexValue, value);
            }
        }

        mMin = min;
        mMax = Math.max(min + 1, max);
        mValue = Math.max(min, Math.min(max, value));
        updateKeyboardRange();

        mIndicatorFormatter = a.getString(R.styleable.DiscreteSeekBar_dsb_indicatorFormatter);

        ColorStateList trackColor = a.getColorStateList(R.styleable.DiscreteSeekBar_dsb_trackColor);
        ColorStateList progressColor = a.getColorStateList(R.styleable.DiscreteSeekBar_dsb_progressColor);
        ColorStateList rippleColor = a.getColorStateList(R.styleable.DiscreteSeekBar_dsb_rippleColor);
        mRipple = SeekBarCompat.getRipple(rippleColor);
        if (isLollipopOrGreater) {
            SeekBarCompat.setBackground(this, mRipple);
        } else {
            mRipple.setCallback(this);
        }


        TrackRectDrawable shapeDrawable = new TrackRectDrawable(trackColor);
        mTrack = shapeDrawable;
        mTrack.setCallback(this);

        shapeDrawable = new TrackRectDrawable(progressColor);
        mScrubber = shapeDrawable;
        mScrubber.setCallback(this);

        ThumbDrawable thumbDrawable = new ThumbDrawable(progressColor, thumbSize);
        mThumb = thumbDrawable;
        mThumb.setCallback(this);
        mThumb.setBounds(0, 0, mThumb.getIntrinsicWidth(), mThumb.getIntrinsicHeight());


        mIndicator = new PopupIndicator(context, attrs, defStyle, convertValueToMessage(mMax));
        mIndicator.setValue(convertValueToMessage(mValue));
        mIndicator.setListener(mFloaterListener);

        a.recycle();

        setNumericTransformer(new DefaulNumericTransformer());

    }

    /**
     * Sets the current Indicator formatter string
     *
     * @param formatter
     * @see String#format(String, Object...)
     * @see #setNumericTransformer(DiscreteSeekBar.NumericTransformer)
     */
    public void setIndicatorFormatter(@Nullable String formatter) {
        mIndicatorFormatter = formatter;
        updateProgressMessage(mValue);
    }

    /**
     * Sets the current {@link DiscreteSeekBar.NumericTransformer}
     *
     * @param transformer
     * @see #getNumericTransformer()
     */
    public void setNumericTransformer(@Nullable NumericTransformer transformer) {
        mNumericTransformer = transformer != null ? transformer : new DefaulNumericTransformer();
        //We need to refresh the PopupIndivator view
        mIndicator.updateSizes(convertValueToMessage(mNumericTransformer.transform(mMax)));
        updateProgressMessage(mValue);
    }

    /**
     * Retrieves the current {@link DiscreteSeekBar.NumericTransformer}
     *
     * @return NumericTransformer
     * @see #setNumericTransformer
     */
    public NumericTransformer getNumericTransformer() {
        return mNumericTransformer;
    }

    /**
     * Sets the maximum value for this DiscreteSeekBar
     * if the supplied argument is smaller than the Current MIN value,
     * the MIN value will be set to MAX-1
     * <p/>
     * <p>
     * Also if the current progress is out of the new range, it will be set to MIN
     * </p>
     *
     * @param max
     * @see #setMin(int)
     * @see #setProgress(int)
     */
    public void setMax(int max) {
        mMax = max;
        if (mMax < mMin) {
            setMin(mMax - 1);
        }
        updateKeyboardRange();

        if (mValue < mMin || mValue > mMax) {
            setProgress(mMin);
        }
    }

    public int getMax() {
        return mMax;
    }

    /**
     * Sets the minimum value for this DiscreteSeekBar
     * if the supplied argument is bigger than the Current MAX value,
     * the MAX value will be set to MIN+1
     * <p>
     * Also if the current progress is out of the new range, it will be set to MIN
     * </p>
     *
     * @param min
     * @see #setMax(int)
     * @see #setProgress(int)
     */
    public void setMin(int min) {
        mMin = min;
        if (mMin > mMax) {
            setMax(mMin + 1);
        }
        updateKeyboardRange();

        if (mValue < mMin || mValue > mMax) {
            setProgress(mMin);
        }
    }

    public int getMin() {
        return mMin;
    }

    /**
     * Sets the current progress for this DiscreteSeekBar
     * The supplied argument will be capped to the current MIN-MAX range
     *
     * @param progress
     * @see #setMax(int)
     * @see #setMin(int)
     */
    public void setProgress(int progress) {
        setProgress(progress, false);
    }

    private void setProgress(int value, boolean fromUser) {
        value = Math.max(mMin, Math.min(mMax, value));
        if (isAnimationRunning()) {
            mPositionAnimator.cancel();
        }

        if (mValue != value) {
            notifyProgress(value, fromUser);
            mValue = value;
            updateProgressMessage(value);
            updateThumbPosFromCurrentProgress();
        }
    }

    /**
     * Get the current progress
     *
     * @return the current progress :-P
     */
    public int getProgress() {
        return mValue;
    }

    /**
     * Sets a listener to receive notifications of changes to the DiscreteSeekBar's progress level. Also
     * provides notifications of when the DiscreteSeekBar shows/hides the bubble indicator.
     *
     * @param listener The seek bar notification listener
     * @see DiscreteSeekBar.OnProgressChangeListener
     */
    public void setOnProgressChangeListener(OnProgressChangeListener listener) {
        mPublicChangeListener = listener;
    }

    private void notifyProgress(int value, boolean fromUser) {
        if (mPublicChangeListener != null) {
            mPublicChangeListener.onProgressChanged(DiscreteSeekBar.this, value, fromUser);
        }
        onValueChanged(value);
    }

    private void notifyBubble(boolean open) {
        if (open) {
            onShowBubble();
        } else {
            onHideBubble();
        }
    }

    /**
     * When the {@link DiscreteSeekBar} enters pressed or focused state
     * the bubble with the value will be shown, and this method called
     * <p>
     * Subclasses may override this to add functionality around this event
     * </p>
     */
    protected void onShowBubble() {
    }

    /**
     * When the {@link DiscreteSeekBar} exits pressed or focused state
     * the bubble with the value will be hidden, and this method called
     * <p>
     * Subclasses may override this to add functionality around this event
     * </p>
     */
    protected void onHideBubble() {
    }

    /**
     * When the {@link DiscreteSeekBar} value changes this method is called
     * <p>
     * Subclasses may override this to add functionality around this event
     * without having to specify a {@link DiscreteSeekBar.OnProgressChangeListener}
     * </p>
     */
    protected void onValueChanged(int value) {
    }

    private void updateKeyboardRange() {
        int range = mMax - mMin;
        if ((mKeyProgressIncrement == 0) || (range / mKeyProgressIncrement > 20)) {
            // It will take the user too long to change this via keys, change it
            // to something more reasonable
            mKeyProgressIncrement = Math.max(1, Math.round((float) range / 20));
        }
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int height = mThumb.getIntrinsicHeight() + getPaddingTop() + getPaddingBottom();
        height += (mAddedTouchBounds * 2);
        setMeasuredDimension(widthSize, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            mIndicator.dismissComplete();
            updateFromDrawableState();
        }
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        super.scheduleDrawable(who, what, when);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int thumbWidth = mThumb.getIntrinsicWidth();
        int thumbHeight = mThumb.getIntrinsicHeight();
        int addedThumb = mAddedTouchBounds;
        int halfThumb = thumbWidth / 2;
        int paddingLeft = getPaddingLeft() + addedThumb;
        int paddingRight = getPaddingRight();
        int bottom = getHeight() - getPaddingBottom() - addedThumb;
        mThumb.setBounds(paddingLeft, bottom - thumbHeight, paddingLeft + thumbWidth, bottom);
        int trackHeight = Math.max(mTrackHeight / 2, 1);
        mTrack.setBounds(paddingLeft + halfThumb, bottom - halfThumb - trackHeight,
                getWidth() - halfThumb - paddingRight - addedThumb, bottom - halfThumb + trackHeight);
        int scrubberHeight = Math.max(mScrubberHeight / 2, 2);
        mScrubber.setBounds(paddingLeft + halfThumb, bottom - halfThumb - scrubberHeight,
                paddingLeft + halfThumb, bottom - halfThumb + scrubberHeight);

        //Update the thumb position after size changed
        updateThumbPosFromCurrentProgress();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        if (!isLollipopOrGreater) {
            mRipple.draw(canvas);
        }
        super.onDraw(canvas);
        mTrack.draw(canvas);
        mScrubber.draw(canvas);
        mThumb.draw(canvas);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        updateFromDrawableState();
    }

    private void updateFromDrawableState() {
        int[] state = getDrawableState();
        boolean focused = false;
        boolean pressed = false;
        for (int i : state) {
            if (i == FOCUSED_STATE) {
                focused = true;
            } else if (i == PRESSED_STATE) {
                pressed = true;
            }
        }
        if (isEnabled() && (focused || pressed)) {
            showFloater();
            mThumb.animateToPressed();
        } else {
            hideFloater();
        }
        mThumb.setState(state);
        mTrack.setState(state);
        mScrubber.setState(state);
        mRipple.setState(state);
    }

    private void updateProgressMessage(int value) {
        mIndicator.setValue(convertValueToMessage(mNumericTransformer.transform(value)));
    }

    private String convertValueToMessage(int value) {
        String format = mIndicatorFormatter != null ? mIndicatorFormatter : DEFAULT_FORMATTER;
        if (mFormatter == null || mFormatter.locale().equals(Locale.getDefault())) {
            int bufferSize = format.length() + String.valueOf(mMax).length();
            mFormatter = new Formatter(new StringBuilder(bufferSize), Locale.getDefault());
        }
        return mFormatter.format(format, value).toString();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        int actionMasked = MotionEventCompat.getActionMasked(event);
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
                if (startDragging(event)) {
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (isDragging()) {
                    updateDragging(event);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopDragging();
                break;
        }
        return isDragging() || super.onTouchEvent(event);
    }

    private boolean startDragging(MotionEvent ev) {
        final Rect bounds = mTempRect;
        mThumb.copyBounds(bounds);
        //Grow the current thumb rect for a bigger touch area
        bounds.inset(-mAddedTouchBounds, -mAddedTouchBounds);
        mIsDragging = (bounds.contains((int) ev.getX(), (int) ev.getY()));
        if (mIsDragging) {
            setPressed(true);
            attemptClaimDrag();
            setHotspot(ev.getX(), ev.getY());
            mDraggOffset = (int) (ev.getX() - bounds.left - mAddedTouchBounds);
        }
        return mIsDragging;
    }

    private boolean isDragging() {
        return mIsDragging;
    }

    private void stopDragging() {
        mIsDragging = false;
        setPressed(false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //TODO: Should we reverse the keys for RTL? The framework's SeekBar does NOT....
        boolean handled = false;
        if (isEnabled()) {
            int progress = getAnimatedProgress();
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    handled = true;
                    if (progress <= mMin) break;
                    animateSetProgress(progress - mKeyProgressIncrement);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    handled = true;
                    if (progress >= mMax) break;
                    animateSetProgress(progress + mKeyProgressIncrement);
                    break;
            }
        }

        return handled || super.onKeyDown(keyCode, event);
    }

    private int getAnimatedProgress() {
        return isAnimationRunning() ? getAnimationTarget() : mValue;
    }


    boolean isAnimationRunning() {
        return mPositionAnimator != null && mPositionAnimator.isRunning();
    }

    void animateSetProgress(int progress) {
        final float curProgress = isAnimationRunning() ? getAnimationPosition() : getProgress();

        if (progress < mMin) {
            progress = mMin;
        } else if (progress > mMax) {
            progress = mMax;
        }
        //setProgressValueOnly(progress);

        if (mPositionAnimator != null) {
            mPositionAnimator.cancel();
        }

        mAnimationTarget = progress;
        mPositionAnimator = AnimatorCompat.create(curProgress,
                progress, new AnimatorCompat.AnimationFrameUpdateListener() {
                    @Override
                    public void onAnimationFrame(float currentValue) {
                        setAnimationPosition(currentValue);
                    }
                });
        mPositionAnimator.setDuration(PROGRESS_ANIMATION_DURATION);
        mPositionAnimator.start();
    }

    private int getAnimationTarget() {
        return mAnimationTarget;
    }

    void setAnimationPosition(float position) {
        mAnimationPosition = position;
        float currentScale = (position - mMin) / (float) (mMax - mMin);
        updateProgressFromAnimation(currentScale);
    }

    float getAnimationPosition() {
        return mAnimationPosition;
    }


    private void updateDragging(MotionEvent ev) {
        setHotspot(ev.getX(), ev.getY());
        int x = (int) ev.getX();
        Rect oldBounds = mThumb.getBounds();
        int halfThumb = oldBounds.width() / 2;
        int addedThumb = mAddedTouchBounds;
        int newX = x - mDraggOffset + halfThumb;
        int left = getPaddingLeft() + halfThumb + addedThumb;
        int right = getWidth() - (getPaddingRight() + halfThumb + addedThumb);
        if (newX < left) {
            newX = left;
        } else if (newX > right) {
            newX = right;
        }

        int available = right - left;
        float scale = (float) (newX - left) / (float) available;
        if (isRtl()) {
            scale = 1f - scale;
        }
        int progress = Math.round((scale * (mMax - mMin)) + mMin);
        setProgress(progress, true);
    }

    private void updateProgressFromAnimation(float scale) {
        Rect bounds = mThumb.getBounds();
        int halfThumb = bounds.width() / 2;
        int addedThumb = mAddedTouchBounds;
        int left = getPaddingLeft() + halfThumb + addedThumb;
        int right = getWidth() - (getPaddingRight() + halfThumb + addedThumb);
        int available = right - left;
        int progress = Math.round((scale * (mMax - mMin)) + mMin);
        //we don't want to just call setProgress here to avoid the animation being cancelled,
        //and this position is not bound to a real progress value but interpolated
        if (progress != getProgress()) {
            mValue = progress;
            notifyProgress(mValue, true);
            updateProgressMessage(progress);
        }
        final int thumbPos = (int) (scale * available + 0.5f);
        updateThumbPos(thumbPos);
    }

    private void updateThumbPosFromCurrentProgress() {
        int thumbWidth = mThumb.getIntrinsicWidth();
        int addedThumb = mAddedTouchBounds;
        int halfThumb = thumbWidth / 2;
        float scaleDraw = (mValue - mMin) / (float) (mMax - mMin);

        //This doesn't matter if RTL, as we just need the "avaiable" area
        int left = getPaddingLeft() + halfThumb + addedThumb;
        int right = getWidth() - (getPaddingRight() + halfThumb + addedThumb);
        int available = right - left;

        final int thumbPos = (int) (scaleDraw * available + 0.5f);
        updateThumbPos(thumbPos);
    }

    private void updateThumbPos(int posX) {
        int thumbWidth = mThumb.getIntrinsicWidth();
        int halfThumb = thumbWidth / 2;
        int start;
        if (isRtl()) {
            start = getWidth() - getPaddingRight() - mAddedTouchBounds;
            posX = start - posX - thumbWidth;
        } else {
            start = getPaddingLeft() + mAddedTouchBounds;
            posX = start + posX;
        }
        mThumb.copyBounds(mInvalidateRect);
        mThumb.setBounds(posX, mInvalidateRect.top, posX + thumbWidth, mInvalidateRect.bottom);
        if (isRtl()) {
            mScrubber.getBounds().right = start - halfThumb;
            mScrubber.getBounds().left = posX + halfThumb;
        } else {
            mScrubber.getBounds().left = start + halfThumb;
            mScrubber.getBounds().right = posX + halfThumb;
        }
        final Rect finalBounds = mTempRect;
        mThumb.copyBounds(finalBounds);
        mIndicator.move(finalBounds.centerX());


        mInvalidateRect.inset(-mAddedTouchBounds, -mAddedTouchBounds);
        finalBounds.inset(-mAddedTouchBounds, -mAddedTouchBounds);
        mInvalidateRect.union(finalBounds);
        SeekBarCompat.setHotspotBounds(mRipple, finalBounds.left, finalBounds.top, finalBounds.right, finalBounds.bottom);
        invalidate(mInvalidateRect);
    }


    private void setHotspot(float x, float y) {
        DrawableCompat.setHotspot(mRipple, x, y);
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == mThumb || who == mTrack || who == mScrubber || who == mRipple || super.verifyDrawable(who);
    }

    private void attemptClaimDrag() {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    private void showFloater() {
        mIndicator.showIndicator(this, mThumb.getBounds());
        notifyBubble(true);
    }

    private void hideFloater() {
        mIndicator.dismiss();
        notifyBubble(false);
    }

    private MarkerDrawable.MarkerAnimationListener mFloaterListener = new MarkerDrawable.MarkerAnimationListener() {
        @Override
        public void onClosingComplete() {
            mThumb.animateToNormal();
        }

        @Override
        public void onOpeningComplete() {

        }

    };

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mIndicator.dismissComplete();
    }

    public boolean isRtl() {
        return (ViewCompat.getLayoutDirection(this) == LAYOUT_DIRECTION_RTL) && mMirrorForRtl;
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        CustomState state = new CustomState(superState);
        state.progress = getProgress();
        state.max = mMax;
        state.min = mMin;
        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state == null || !state.getClass().equals(CustomState.class)) {
            super.onRestoreInstanceState(state);
            return;
        }

        CustomState customState = (CustomState) state;
        setMin(customState.min);
        setMax(customState.max);
        setProgress(customState.progress, false);
        super.onRestoreInstanceState(customState.getSuperState());
    }

    static class CustomState extends BaseSavedState {
        private int progress;
        private int max;
        private int min;

        public CustomState(Parcel source) {
            super(source);
            progress = source.readInt();
            max = source.readInt();
            min = source.readInt();
        }

        public CustomState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel outcoming, int flags) {
            super.writeToParcel(outcoming, flags);
            outcoming.writeInt(progress);
            outcoming.writeInt(max);
            outcoming.writeInt(min);
        }

        public static final Creator<CustomState> CREATOR =
                new Creator<CustomState>() {

                    @Override
                    public CustomState[] newArray(int size) {
                        return new CustomState[size];
                    }

                    @Override
                    public CustomState createFromParcel(Parcel incoming) {
                        return new CustomState(incoming);
                    }
                };
    }
}

