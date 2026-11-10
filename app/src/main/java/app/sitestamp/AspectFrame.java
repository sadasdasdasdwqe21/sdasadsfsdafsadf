package app.sitestamp;

import android.content.Context;
import android.widget.FrameLayout;

/** A box that keeps a fixed width-to-height shape and is as big as its parent allows. */
final class AspectFrame extends FrameLayout {
    private float ratio = 3f / 4f;

    AspectFrame(Context c) {
        super(c);
    }

    /** @param widthOverHeight for example 3/4 for an upright photo */
    void setRatio(float widthOverHeight) {
        if (Math.abs(ratio - widthOverHeight) > 0.001f) {
            ratio = widthOverHeight;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int outW = w;
        int outH = Math.round(w / ratio);
        if (outH > h) {
            outH = h;
            outW = Math.round(h * ratio);
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(outW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(outH, MeasureSpec.EXACTLY));
    }
}
