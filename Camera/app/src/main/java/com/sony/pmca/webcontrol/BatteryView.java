package com.sony.pmca.webcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom View drawing a native Sony-style battery icon with proportional level fill.
 */
public class BatteryView extends View {
    private final Paint m_borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint m_capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint m_fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF m_bodyRect = new RectF();
    private final RectF m_capRect = new RectF();
    private final RectF m_fillRect = new RectF();

    private int m_percent = 0;

    public BatteryView(Context context) {
        super(context);
        init();
    }

    public BatteryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BatteryView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        m_borderPaint.setColor(Color.WHITE);
        m_borderPaint.setStyle(Paint.Style.STROKE);
        m_borderPaint.setStrokeWidth(1.5f);

        m_capPaint.setColor(Color.WHITE);
        m_capPaint.setStyle(Paint.Style.FILL);

        m_fillPaint.setColor(Color.parseColor("#00FF88"));
        m_fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setPercent(int percent) {
        m_percent = Math.max(0, Math.min(100, percent));
        if (m_percent < 15) {
            m_fillPaint.setColor(Color.parseColor("#FF5555"));
        } else if (m_percent <= 30) {
            m_fillPaint.setColor(Color.parseColor("#FFD700"));
        } else {
            m_fillPaint.setColor(Color.parseColor("#00FF88"));
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 4 || h <= 4) return;

        float capW = Math.max(2f, w * 0.10f);
        float bodyRight = w - capW - 1f;
        float pad = 1.5f;

        // Battery body outline
        m_bodyRect.set(pad, pad, bodyRight, h - pad);
        canvas.drawRect(m_bodyRect, m_borderPaint);

        // Positive terminal nub on the right
        float capTop = h * 0.28f;
        float capBot = h * 0.72f;
        m_capRect.set(bodyRight, capTop, w - 1f, capBot);
        canvas.drawRect(m_capRect, m_capPaint);

        // Inner fill bar
        float innerPad = pad + 2.0f;
        float maxFillW = (bodyRight - innerPad) - innerPad;
        if (maxFillW > 0 && m_percent > 0) {
            float fillW = maxFillW * (m_percent / 100.0f);
            m_fillRect.set(innerPad, innerPad, innerPad + fillW, h - innerPad);
            canvas.drawRect(m_fillRect, m_fillPaint);
        }
    }
}
