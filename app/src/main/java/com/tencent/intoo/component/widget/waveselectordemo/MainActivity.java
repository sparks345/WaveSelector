package com.tencent.intoo.component.widget.waveselectordemo;

import java.util.ArrayList;

import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import com.tencent.intoo.component.widget.waveselector.WaveSelector;
import com.tencent.intoo.component.widget.waveselector.WaveSelector.IWaveSelectorListener;

public class MainActivity extends AppCompatActivity implements IWaveSelectorListener, OnClickListener {

    private static final String TAG = "MainActivity";

    private WaveSelector mSelector;
    private Button mBtnPause;
    private Button mBtnStart;
    private Button mBtnResume;
    private Button mBtnSeek;
    private TextView mTextView;
    private TextView mTextScrollint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        assignViews();
        initData();

        int ss = WaveSelector.calcSampleStep(44100, 72, 40);
        Log.i(TAG, "SAMPLE_STEP:" + ss);
    }

    private void assignViews() {
        mSelector = (WaveSelector) findViewById(R.id.selector);
        mBtnPause = (Button) findViewById(R.id.btn_pause);
        mBtnStart = (Button) findViewById(R.id.btn_start);
        mBtnResume = (Button) findViewById(R.id.btn_resume);
        mBtnSeek = (Button) findViewById(R.id.btn_seek);
        mTextView = (TextView) findViewById(R.id.textView);
        mTextScrollint = (TextView) findViewById(R.id.txt_scrolling);

        mBtnPause.setOnClickListener(this);
        mBtnStart.setOnClickListener(this);
        mBtnResume.setOnClickListener(this);
        mBtnSeek.setOnClickListener(this);
    }


    private void initData() {
        final ArrayList<Integer> ll = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ll.add((int) (Math.random() * 65535));
        }

//        mSelector.setData(ll);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                mSelector.setPlayDuration(5000);
                mSelector.setListener(MainActivity.this);
                mSelector.setData(ll);
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mSelector.seekTo(4000);
                    }
                }, 2000);

            }
        }, 1000);
//        new Handler().postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                mSelector.startHighLight(0, 15000);
//            }
//        }, 1000);
    }

    @Override
    public void onChanging(long timeStart) {
        mTextScrollint.setText(String.valueOf(timeStart));
    }

    @Override
    public void onSelect(long timeStart) {
        Log.v(TAG, "callback .. on select.." + timeStart);
        mSelector.startHighLight();
    }

    @Override
    public void onReady() {

    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_pause:
                mSelector.stopHighLight();
                break;
            case R.id.btn_start:
                mSelector.startHighLight();
                break;
            case R.id.btn_resume:
                mSelector.resumeHighLight();
                break;
            case R.id.btn_seek:
                int ts = Integer.parseInt(String.valueOf(mTextView.getText()), 10);
                mSelector.seekHighLightToTime(ts);
                break;
        }
    }
}
