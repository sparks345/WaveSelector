package com.tencent.intoo.component.widget.waveselectordemo;

import java.util.ArrayList;

import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import com.tencent.intoo.component.widget.waveselector.WaveSelector;
import com.tencent.intoo.component.widget.waveselector.WaveSelector.IWaveSelectorListener;

public class MainActivity extends AppCompatActivity implements IWaveSelectorListener {

    private static final String TAG = "MainActivity";

    private WaveSelector mSelector;

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
                mSelector.seekTo(2000);
                mSelector.setData(ll);
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

    }

    @Override
    public void onSelect(long timeStart) {
        mSelector.startHighLight();
    }

    @Override
    public void onReady() {

    }
}
