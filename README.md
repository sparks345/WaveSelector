# WaveSelector
WaveSelector

It's looks like this:<br/>
![demo](https://raw.githubusercontent.com/sparks345/WaveSelector/master/md/demo.png)

you can modify color config in xml:
```xml
<com.tencent.intoo.component.widget.waveselector.WaveSelector
    android:id="@+id/selector"
    android:layout_width="match_parent"
    android:layout_height="100dp"
    android:background="@color/colorPrimaryDark"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintLeft_toLeftOf="parent"
    app:layout_constraintRight_toRightOf="parent"
    app:layout_constraintTop_toTopOf="parent"
    app:wave_playing_color_end_color="#ffff00"
    app:wave_playing_color_start_color="#ff0000"/>
```

more custom attributes:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <declare-styleable name="WaveSelector">
        <!--波形颜色-->
        <attr name="wave_color" format="color|reference"/>
        <!--波形圆角尺寸-->
        <attr name="wave_corner_size" format="dimension"/>
        <!--波形播放起始点颜色-->
        <attr name="wave_playing_color_start_color" format="color|reference"/>
        <!--波形播放终止点颜色-->
        <attr name="wave_playing_color_end_color" format="color|reference"/>
        <!--波形选择中线颜色-->
        <attr name="wave_select_line_color" format="color|reference"/>
        <!--波形选择中线到最小时长时的颜色-->
        <attr name="wave_select_line_on_limit_color" format="color|reference"/>
        <!--波形顶部间隙-->
        <attr name="wave_padding_top" format="dimension"/>
        <!--波形底部间隙-->
        <attr name="wave_padding_bottom" format="dimension"/>
        <!--满屏宽度映射的时长-->
        <attr name="full_width_track_duration" format="integer"/>
        <!--半屏对应的波形数-->
        <attr name="half_wave_count" format="integer"/>
        <!--滚动速率-->
        <attr name="scrolling_velocity" format="integer"/>
    </declare-styleable>
</resources>
```

You can view usage in ```MainActivity.java```