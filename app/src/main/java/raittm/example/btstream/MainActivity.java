package raittm.example.btstream;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;


public class MainActivity extends AppCompatActivity implements ActionBar.TabListener {
    private static final String TAG = "btStream";
    private static boolean D=true;

    private final UUID DATA_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private final UUID CONTROL_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fc");

    BluetoothAdapter mBluetoothAdapter;
    BluetoothDevice mDevice;

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE = 1;

    int mState;

    public static final int CMD_FILEINFO=1;
    public static final int CMD_BUFFERSTART=2;
    public static final int CMD_BUFFEREND=3;
    public static final int CMD_PLAY=4;
    public static final int CMD_STOP=5;
    public static final int CMD_VOLUMEUP=6;
    public static final int CMD_VOLUMEDOWN=7;
    public static final int CMD_FILESIZE=8;
    public static final int CMD_DISCONNECT=9;
    public static final int CMD_PAUSE=10;
    public static final int CMD_HEARTBEAT=11;

    public static final int STS_PLAYFINISHED=101;
    public static final int STS_ACK=102;
    public static final int STS_CONNECTED=103;
    public static final int STS_DISCONNECTED=104;

    public static final int REFRESH_QUEUEVIEW=201;

    public static final int SELECT_TAB=0;
    public static final int QUEUE_TAB=1;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    public static final int DIR_NONE=0;
    public static final int DIR_LEFT=1;
    public static final int DIR_RIGHT=2;

    PowerManager.WakeLock wakeLock;
    NotificationManager nm;

    //SettingsContentObserver sco;
    //AudioManager.OnAudioFocusChangeListener afChangeListener;

    ControlConnectThread mControlConnectThread;
    ControlThread mControlThread;

    AcceptThread mAcceptThread;

    DataThread mDataThread;

    List<String> dirs;
    ListView selectListView;
    ArrayAdapter<String> selectAdapter =null;
    File currentDir;
    File previousDir;

    public class SettingsContentObserver extends ContentObserver {
        int previousVolume;
        Context context;

        public SettingsContentObserver(Context c, Handler handler) {
            super(handler);
            context = c;

            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        }

        @Override
        public boolean deliverSelfNotifications() {
            return super.deliverSelfNotifications();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int currentVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);

            int delta = previousVolume - currentVolume;

            if (delta > 0) {
                Log.d(TAG, "Vol down");
                previousVolume = currentVolume;
            } else if (delta < 0) {
                Log.d(TAG, "Vol up");
                previousVolume = currentVolume;
            }
        }
    }

    public class PlayQueueListItem {
        private String name;
        private boolean isPlaying;
        private boolean isStopped;

        public PlayQueueListItem(String s) {
            this.name=s;
            this.isPlaying=false;
            this.isStopped=true;
        }
        public void setName(String s) {
            this.name=s;
        }
        public String getName() {
            return name;
        }
        public void play() {
            this.isPlaying=true;
            this.isStopped=false;
        }
        public boolean isPlaying() {
            if (this.isPlaying && !this.isStopped) return true;
            return false;
        }
        public void pause() {
            this.isStopped=true;
        }
        public boolean isPaused() {
            if (this.isPlaying && this.isStopped) return true;
            return false;
        }
        public void stop() {
            this.isPlaying=false;
            this.isStopped=true;
        }
        public boolean isStopped() {
            if (!this.isPlaying && this.isStopped) return true;
            return false;
        }
    }

    List<PlayQueueListItem> playQueueList;
    ListView queueListView;
    SwipeArrayAdapter queueAdapter;
    public View.OnTouchListener gestureListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String mruAddress=null;

        super.onCreate(savedInstanceState);

        ActionBar bar=getSupportActionBar();
        bar.addTab(bar.newTab().setText("Files").setTabListener(this));
        bar.addTab(bar.newTab().setText("Playlist").setTabListener(this));
        bar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        setContentView(R.layout.select_tab);

        PowerManager powerManager=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock= powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"btStream");
        wakeLock.acquire();

        nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);

/*
        AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
        afChangeListener=new AudioManager.OnAudioFocusChangeListener() {
            public void onAudioFocusChange(int fc) {
                Log.d(TAG,"Audio focus change "+fc);
            }
        };
        am.requestAudioFocus(afChangeListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);

        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        sco=new SettingsContentObserver(this, new Handler());
        getApplicationContext().getContentResolver().registerContentObserver(Settings.System.CONTENT_URI,true,sco);
*/

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter==null)
        {
            Toast.makeText(this, "No bluetooth adapter", Toast.LENGTH_SHORT).show();
        }

        if (!mBluetoothAdapter.isEnabled())
        {
            Toast.makeText(this, "Please enable bluetooth", Toast.LENGTH_LONG).show();
            //Intent enableBtIntent=new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            //startActivityForResult(enableBtIntent, 1);
            finish();

        }
        else {
            Properties prop=new Properties();
            try {
                prop.load(new FileInputStream(getCacheDir().getAbsolutePath()+"btstream.properties"));

                if (D) Log.d(TAG,""+prop.getProperty("currentDir"));
                currentDir=new File(prop.getProperty("currentDir"));

                //mruAddress=prop.getProperty("lastUsedDeviceAddress");
            } catch (IOException e) {
                Log.e(TAG,"Problem getting properties");

                currentDir=new File(Environment.getExternalStorageDirectory().getAbsolutePath());
            }

            previousDir=new File(currentDir.getAbsolutePath());
            dirs = new ArrayList<String>();

            fillSelectListViewWithFilenames(currentDir);

            playQueueList = new ArrayList<PlayQueueListItem>();

            //if (mruAddress!=null) connectToPreviousDevice(mruAddress);

            bar.selectTab(bar.getTabAt(0));

        }
    }

    @Override
    public void onTabSelected(ActionBar.Tab tab, FragmentTransaction ft) {
        switch (tab.getPosition()) {
            case SELECT_TAB:
                setContentView(R.layout.select_tab);
                if (currentDir!=null) fillSelectListViewWithFilenames(currentDir);

                break;
            case QUEUE_TAB:
                setContentView(R.layout.queue_tab);

                fillQueueListView();

                break;
            default:
        }
    }

    @Override
    public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction ft) {

    }

    @Override
    public void onTabReselected(ActionBar.Tab tab, FragmentTransaction ft) {

    }

    public class SwipeArrayAdapter extends ArrayAdapter implements View.OnTouchListener {

        private static final String TAG = "SwipeArrayAdapter";
        private float mLastX;

        private List<PlayQueueListItem> items;
        private int resource;
        private Context context;

        //public SwipeArrayAdapter(Context context, int resource, int textViewResourceId, List<PlayQueueItem> items) {
        //    super(context, resource, textViewResourceId);
        public SwipeArrayAdapter(Context context, int resource, List<PlayQueueListItem> items) {
            super(context, resource, items);

            this.resource=resource;
            this.context=context;
            this.items=items;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            View row=convertView;
            PlayQueueHolder holder=null;

            LayoutInflater inflater=((Activity)context).getLayoutInflater();
            row=inflater.inflate(resource,parent,false);

            holder=new PlayQueueHolder();
            holder.playQueueItem=items.get(position);
            holder.name=(TextView)row.findViewById(R.id.queueitem_name);
            holder.pauseButton = (ImageButton) row.findViewById(R.id.queueitem_pause);

            if (items.get(position).isPlaying()) {
                holder.pauseButton.setImageResource(android.R.drawable.ic_media_pause);
            } else {
                // stopped or paused
                holder.pauseButton.setImageResource(android.R.drawable.ic_media_play);
            }

            if (position!=0) {
                holder.pauseButton.setVisibility(View.INVISIBLE);
            }

            ((TextView)row.findViewById(R.id.queueitem_name)).setMaxLines(1);
            row.setTag(holder);

            setupItem(holder);

            row.setOnTouchListener(this);

            return row;
        }

        private void setupItem(PlayQueueHolder holder) {
            String s=holder.playQueueItem.getName();

            holder.name.setText(s.substring(s.lastIndexOf('/')+1));
        }

        private static final int MIN_THRESHOLD = 20;
        private static final int MAX_THRESHOLD = 300; // maybe half of listView width?
        private boolean motionInterceptDisallowed=false;
        private boolean swipeHandled;
        private int direction=DIR_NONE;
        int initialX = 0;
        int initialY = 0;
        final float slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();

        @Override
        public boolean onTouch(final View view, MotionEvent motionEvent) {
            if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                initialX = (int) motionEvent.getX();
                initialY = (int) motionEvent.getY();

                swipeHandled=false;
                //view.setPadding(0, 0, 0, 0);
            } else if (motionEvent.getAction() == MotionEvent.ACTION_MOVE) {

                // don't swipe on first item
                //if (((ListView) view.getParent()).getPositionForView(view)==0) return false;

                int currentX = (int) motionEvent.getX();
                int offset = currentX - initialX;
                if (Math.abs(offset) > slop) {
                    view.setPadding(offset, 0, 0, 0);

                    if (offset > MIN_THRESHOLD && !motionInterceptDisallowed) {
                        queueListView.requestDisallowInterceptTouchEvent(true);
                        motionInterceptDisallowed=true;
                        return true;
                    } else if (offset < -MIN_THRESHOLD && !motionInterceptDisallowed) {
                        queueListView.requestDisallowInterceptTouchEvent(true);
                        motionInterceptDisallowed=true;
                        return true;
                    }

                    if (offset > MAX_THRESHOLD && !swipeHandled) {
                        swipeHandled=true;
                        direction=DIR_RIGHT;
                        if (D) Log.d(TAG, "Left to right "+((ListView) view.getParent()).getPositionForView(view));
                    }

                    if (offset < -MAX_THRESHOLD && !swipeHandled) {
                        swipeHandled = true;
                        direction=DIR_LEFT;
                        if (D) Log.d(TAG, "Right to left "+((ListView) view.getParent()).getPositionForView(view));
                    }


                }
            } else if (motionEvent.getAction() == MotionEvent.ACTION_UP || motionEvent.getAction() == MotionEvent.ACTION_CANCEL) {
                if (motionInterceptDisallowed) {
                    // then we were swiping
                    queueListView.requestDisallowInterceptTouchEvent(false);
                    motionInterceptDisallowed=false;

                    int pos=((ListView) view.getParent()).getPositionForView(view);

                    if (D) Log.d(TAG,"Swiping ");

                    if (direction==DIR_LEFT) {
                        if (D) Log.d(TAG,"No action for left swipe");

                        // implement any left swipe actions
                    } else if (direction==DIR_RIGHT) {
                        if (D) Log.d(TAG,"Deleting "+pos);

                        if (pos==0) {
                            if ((mDataThread!=null) && mDataThread.isAlive()) {
                                mDataThread.cancel();
                                mDataThread=null;
                            }
                            if (mControlThread!=null) {
                                mControlThread.sendBufferEnd();
                                mControlThread.sendStop();
                            }
                            if (playQueueList.get(0).isPlaying() || playQueueList.get(0).isPaused()) {
                                mHandler.obtainMessage(STS_PLAYFINISHED).sendToTarget();
                            } else {
                                playQueueList.remove(pos);
                                mHandler.obtainMessage(REFRESH_QUEUEVIEW).sendToTarget();
                            }
                        } else {
                            playQueueList.remove(pos);
                            mHandler.obtainMessage(REFRESH_QUEUEVIEW).sendToTarget();
                        }
                    }


                    direction=DIR_NONE;

                    ValueAnimator animator = ValueAnimator.ofInt(view.getPaddingLeft(), 0);
                    animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator valueAnimator) {
                            view.setPadding((Integer) valueAnimator.getAnimatedValue(), 0, 0, 0);
                        }
                    });
                    animator.setDuration(150);
                    animator.start();
                } else {
                    if (Math.abs(initialY-motionEvent.getY())<MIN_THRESHOLD) {
                        // it wasn't a scroll
                        int pos=((ListView) view.getParent()).getPositionForView(view);
                        if (D) Log.d(TAG, "Tapped " +pos);
                        queueListView.performItemClick(view, pos, view.getId());
                    }
                }
            }

            return true;
        }

    }

    public static class PlayQueueHolder {
        PlayQueueListItem playQueueItem;
        TextView name;
        ImageButton pauseButton;
    }

    public void playQueuePauseOnClick(View v) {
        ListView lv=(ListView)(v.getParent()).getParent();
        int pos=lv.getPositionForView(v);

        if (pos==0) {
            //PlayQueueAdapterItem pqi=(PlayQueueAdapterItem)((PlayQueueHolder)((View)v.getParent()).getTag()).playQueueAdapterItem;

            if (playQueueList.get(0).isPlaying()) {
                if (D) Log.d(TAG, "Pausing");

                playQueueList.get(0).pause();

                ((ImageButton) v).setImageResource(android.R.drawable.ic_media_play);

                mHandler.obtainMessage(CMD_PAUSE).sendToTarget();

            } else if (playQueueList.get(0).isPaused()) {
                if (D) Log.d(TAG, "Playing");

                playQueueList.get(0).play();

                ((ImageButton) v).setImageResource(android.R.drawable.ic_media_pause);

                mHandler.obtainMessage(CMD_PAUSE).sendToTarget();

            } else {
                if (mControlThread != null && mControlThread.isAlive()) {
                    if (!playQueueList.isEmpty()) {
                        File selectedFile = new File(playQueueList.get(0).getName());

                        if (D) Log.d(TAG, "Playing next " + selectedFile.getName());

                        mControlThread.sendFileName(selectedFile.getName());
                        mControlThread.sendFileSize(selectedFile.length());

                        if (mAcceptThread != null) {
                            mAcceptThread.cancel();
                            mAcceptThread = null;
                        }
                        if (mDataThread != null) {
                            mDataThread.cancel();
                            mDataThread = null;
                        }

                        mState = STATE_LISTEN;
                        mAcceptThread = new AcceptThread();
                        mAcceptThread.start();

                        mControlThread.sendBufferStart();
                        mControlThread.sendPlay();

                        playQueueList.get(0).play();

                        ((ImageButton) v).setImageResource(android.R.drawable.ic_media_pause);
                    } else {
                        if (D) Log.d(TAG, "Nothing to play in queue (or not at head)");
                    }
                } else {
                    Toast.makeText(getApplicationContext(), "Please connect to paired device", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void fillQueueListView() {
        //for (String s: playQueue) {
        //    Log.d(TAG,""+s);
        //}

        queueAdapter = new SwipeArrayAdapter(this, R.layout.playqueue_item, playQueueList);

        queueListView = (ListView) findViewById(R.id.queue_listView);
        queueListView.setAdapter(queueAdapter);

        queueListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                // only start playing from head of queue (ie if first item clicked)
                if (i!=0) {
                    playQueueList.add(1, playQueueList.get(i));
                    playQueueList.remove(i + 1);
                    //fillQueueListView();
                    mHandler.obtainMessage(REFRESH_QUEUEVIEW).sendToTarget();

                }
            }
        });
    }

    private void fillPlayQueueFromDir(File f) {
        File[] dirContent= f.listFiles();

        for(File ff: dirContent)
        {
            //Log.i(TAG, ff.getAbsolutePath());

            if (!ff.isHidden()) {
                if (ff.isFile() && ff.getName().toLowerCase().endsWith(".mp3")) {
                    playQueueList.add(new PlayQueueListItem(ff.getAbsolutePath()));
                }
            }
        }
    }

    private void fillSelectListViewWithFilenames(File f) {

        File[] dirContent= f.listFiles();
        ArrayList<String> files = new ArrayList<String>();
        ArrayList<String> nameOnlyDirs = new ArrayList<String>();

        dirs.clear();

        for(File ff: dirContent)
        {
            //Log.i(TAG, ff.getAbsolutePath());

            if (!ff.isHidden()) {
                if (ff.isFile() && ff.getName().toLowerCase().endsWith(".mp3")) {
                    files.add(ff.getAbsolutePath());
                }
                if (ff.isDirectory()) {
                    dirs.add(ff.getAbsolutePath());
                }
            }
        }
        Collections.sort(files);
        Collections.sort(dirs);

        dirs.addAll(files);

        if (!currentDir.getAbsolutePath().equals(("/"))) dirs.add(0, currentDir+"/..");

        for (String s: dirs) {
            nameOnlyDirs.add(s.substring(s.lastIndexOf('/')+1));
        }

        selectAdapter =new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,android.R.id.text1);
        selectAdapter.addAll(nameOnlyDirs);

        selectListView =(ListView)findViewById(R.id.select_listView);
        selectListView.setAdapter(selectAdapter);

        selectListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> adapterView, View view, int i, long l) {

                File selectedFile = new File(dirs.get(i));

                if (selectedFile.exists()) {
                    if (selectedFile.isDirectory()) {
                        fillPlayQueueFromDir(selectedFile);

                        Toast.makeText(getApplicationContext(), "Added directory " + selectedFile.getName() + " to queue", Toast.LENGTH_SHORT).show();
                    } else if (selectedFile.isFile()) {
                        playQueueList.add(new PlayQueueListItem(selectedFile.getAbsolutePath()));

                        Toast.makeText(getApplicationContext(), "Added file " + selectedFile.getName() + " to queue", Toast.LENGTH_SHORT).show();
                    }
                }

                return true;
            }
        });

        selectListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {

                File selectedFile = new File(dirs.get(i));

                if (selectedFile.exists()) {
                    if (selectedFile.isFile()) {
                        if (D) Log.d(TAG, "Selected " + selectedFile);

                        if (mControlThread != null && mControlThread.isAlive()) {

                            // add to head of queue and interrupt current playing
                            playQueueList.add(0, new PlayQueueListItem(selectedFile.getAbsolutePath()));

                            mControlThread.sendFileName(selectedFile.getName());
                            mControlThread.sendFileSize(selectedFile.length());

                            if (mAcceptThread != null) {
                                mAcceptThread.cancel();
                                mAcceptThread = null;
                            }
                            if (mDataThread != null) {
                                mDataThread.cancel();
                                mDataThread = null;
                            }

                            mState = STATE_LISTEN;
                            mAcceptThread = new AcceptThread();
                            mAcceptThread.start();

                            mControlThread.sendBufferStart();
                            mControlThread.sendPlay();

                            // set the status for the listview pause button
                            playQueueList.get(0).play();
                        } else {
                            Toast.makeText(getApplicationContext(), "Please connect to paired device", Toast.LENGTH_SHORT).show();
                        }
                    } else if (selectedFile.isDirectory() && selectedFile.getAbsolutePath().contains("..")) {
                        previousDir = currentDir;
                        currentDir = selectedFile.getParentFile().getParentFile().getAbsoluteFile();
                        fillSelectListViewWithFilenames(currentDir);

                        selectListView.post(new Runnable() {

                            @Override
                            public void run() {

                                selectListView.setSelection(dirs.indexOf(previousDir.getAbsolutePath()));
                                selectListView.clearFocus();
                            }
                        });

                    } else if (selectedFile.isDirectory()) {
                        currentDir = new File(selectedFile.getAbsolutePath());
                        fillSelectListViewWithFilenames(currentDir);
                    }

                    Properties prop = new Properties();
                    try {
                        prop.setProperty("currentDir", currentDir.getAbsolutePath());

                        prop.store(new FileOutputStream(getCacheDir().getAbsolutePath() + "btstream.properties"), null);
                    } catch (IOException e) {
                        Log.e(TAG, "Problem saving currentDir property");
                    }
                }
            }
        });
    }

    public void cleanUp() {

        if (D) Log.d(TAG, "Cleaning up");

        if (mControlThread!=null) mControlThread.sendDisconnect();

        if (mControlConnectThread != null) {
            mControlConnectThread.cancel();
            mControlConnectThread = null;
        }
        if (mControlThread != null) {
            mControlThread.cancel();
            mControlThread = null;
        }
        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }
        if (mDataThread != null) {
            mDataThread.cancel();
            mDataThread = null;
        }

        nm.cancelAll();

        //Properties prop = new Properties();
        //try {
        //    prop.setProperty("currentDir", currentDir.getAbsolutePath());

        //    prop.store(new FileOutputStream(getCacheDir().getAbsolutePath()+"btstream.properties"), null);
        //} catch (IOException e) {Log.e(TAG,"Problem saving properties");}
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int action = e.getAction();
        int keyCode = e.getKeyCode();

        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (action==KeyEvent.ACTION_DOWN) {
                    if (mControlThread!=null) mControlThread.sendVolumeUp();
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (action==KeyEvent.ACTION_DOWN) {
                    if (mControlThread!=null) mControlThread.sendVolumeDown();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (D) Log.d(TAG, "Start");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        wakeLock.release();
        nm.cancelAll();

/*
        getApplicationContext().getContentResolver().unregisterContentObserver(sco);
        AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
        am.abandonAudioFocus(afChangeListener);
*/

        cleanUp();

        if (D) Log.d(TAG, "Destroying");

    }

    @Override
    protected void onStop() {
        super.onStop();

        if (D) Log.d(TAG, "Stopping");

        PendingIntent pi= PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        NotificationCompat.Builder n = new NotificationCompat.Builder(this)
                .setContentTitle("btStream is running")
                .setContentText("Click here to open btStream")
                .setSmallIcon(R.drawable.ic_bluetooth_audio_white_24dp)
                .setContentIntent(pi)
                .setAutoCancel(true);

        nm.notify(1, n.build());
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (D) Log.d(TAG, "Resuming");

        nm.cancelAll();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_exit) {

            //cleanUp();
            finish();

            return true;
        }
        else if (id == R.id.action_connect)
        {
            cleanUp();

            Intent serverIntent = new Intent(this, DeviceListActivity.class);
            startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
            return true;

        }
        //else if (id == R.id.action_disconnect)
        //{
        //    cleanUp();
        //}

        return super.onOptionsItemSelected(item);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == AppCompatActivity.RESULT_OK) {
                    connectDevice(data, true);

                }
                break;
        }
    }
/*
    private void connectToPreviousDevice(String address) {
        if (D) Log.d(TAG,"Attempting connection to a previous device");

        mDevice = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device

        mDataConnectThread = new DataConnectThread(mDevice);
        mDataConnectThread.start();

        mAcceptThread = new AcceptThread();
        mAcceptThread.start();

        mState=STATE_LISTEN;
    }
*/
    private void connectDevice(Intent data, boolean secure) {
        // Get the device MAC address
        String address = data.getExtras()
                .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        // Get the BluetoothDevice object
        mDevice = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device

        mControlConnectThread=new ControlConnectThread(mDevice);
        mControlConnectThread.start();

        mState=STATE_LISTEN;
    }

    private class ControlConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ControlConnectThread(BluetoothDevice device) {
            BluetoothSocket tmp=null;
            mmDevice = device;
            try {
                tmp=device.createRfcommSocketToServiceRecord(CONTROL_UUID);
            } catch (IOException e) {}
            mmSocket=tmp;
        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN ControlConnectThread");

            mBluetoothAdapter.cancelDiscovery();
            try {
                mmSocket.connect();
            } catch (IOException connectException) {
                try {
                    mmSocket.close();
                } catch (IOException closeException) {}
                return;
            }

            mControlThread = new ControlThread(mmSocket);
            mControlThread.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
            mControlThread.start();

        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {}
        }
    }

    private class ControlThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private final DataOutputStream dos;
        private final DataInputStream dis;

        private boolean finished=false;

        public ControlThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn=null;
            OutputStream tmpOut=null;
            try {
                tmpIn=socket.getInputStream();
                tmpOut=socket.getOutputStream();
            } catch (IOException e) {}

            mmInStream=tmpIn;
            mmOutStream=tmpOut;

            dos=new DataOutputStream(mmOutStream);
            dis=new DataInputStream(mmInStream);

        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN ControlThread");

            mHandler.obtainMessage(STS_CONNECTED).sendToTarget();

            int msgLength=0;
            int avail=0;
            boolean waitingForLength=true;
            while (!finished) {
                //synchronized (MainActivity.this) {
                {
                    try {
                        if (waitingForLength) {
                            msgLength = dis.readInt();

                            waitingForLength = false;
                        } else {
                            if (dis.available() > (msgLength)) {

                                int n = msgLength;
                                char c;
                                StringBuffer s = new StringBuffer(n);

                                s.delete(0, s.length());
                                while (n > 0) {
                                    s.append(dis.readChar());
                                    n--;
                                }
                                waitingForLength = true;

                                String[] keyvalue = s.toString().split("=");
                                if (D) Log.d(TAG, "" + keyvalue[0] + " " + keyvalue[1]);

                                if (keyvalue[0].equals("Status")) {
                                    if (keyvalue[1].equals("PlayFinished")) {
                                        mHandler.obtainMessage(STS_PLAYFINISHED).sendToTarget();
                                    }
                                }

                            }
                        }
                        Thread.sleep(100);
                    } catch (IOException e) {
                        Log.e(TAG, "Problem reading control stream " + e);

                        // TODO: should restart the Accept() server to allow reconnect
                        mHandler.obtainMessage(STS_DISCONNECTED).sendToTarget();

                        break;
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) {}
        }

        public void sendVolumeUp() {
            String ss="Cmd=VolumeUp";
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}
        }
        public void sendVolumeDown() {
            String ss="Cmd=VolumeDown";
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}
        }
        public void sendBufferStart() {
            String ss="Cmd=BufferStart";
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}

        }
        public void sendBufferEnd() {
            String ss="Cmd=BufferEnd";
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}

            mHandler.obtainMessage(CMD_BUFFEREND).sendToTarget();
        }
        public void sendPlay() {
            String ss="Cmd=Play";
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}
        }
        public void sendPause() {
            String ss="Cmd=Pause";
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}
        }
        public void sendStop() {
            String ss="Cmd=Stop";
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}
        }

        public void sendDisconnect() {
            String ss="Cmd=Disconnect";
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}
        }

        public void sendFileName(String s) {
            String ss="NowPlaying="+s;
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}

            mHandler.obtainMessage(CMD_FILEINFO, s).sendToTarget();
        }

        public void sendFileSize(long l) {
            String ss="FileSize="+l;
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}

        }

        public void cancel() {
            finished=true;

            try {
                if (dos!=null) dos.close();
                if (dis!=null) dis.close();

                mmInStream.close();
                mmOutStream.close();
                mmSocket.close();
            } catch (IOException e) {Log.d(TAG,"Problem cancelling ControlThread "+e);}
        }
    }

    Handler mHandler = new Handler() {
        String s="";

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CMD_FILEINFO:
                    s=((String)msg.obj);

                    // set NowPlaying label text in UI
                    //TextView nowPlayingLabel = (TextView) findViewById(R.id.nowPlaying);
                    //nowPlayingLabel.setText(s);

                    break;
                case CMD_BUFFERSTART:
                    break;
                case CMD_BUFFEREND:
                    Toast.makeText(MainActivity.this, "Finished streaming "+s, Toast.LENGTH_SHORT).show();
                    break;
                case CMD_PLAY:
                    break;
                case CMD_STOP:
                    break;
                case CMD_VOLUMEUP:
                    break;
                case CMD_VOLUMEDOWN:
                    break;
                case CMD_PAUSE:
                    if (mControlThread!=null) mControlThread.sendPause();
                    break;
                case REFRESH_QUEUEVIEW:
                    fillQueueListView();
                    break;

                case STS_CONNECTED:
                    getSupportActionBar().setBackgroundDrawable(new ColorDrawable(Color.rgb(0, 200, 0)));
                    break;

                case STS_DISCONNECTED:
                    getSupportActionBar().setBackgroundDrawable(new ColorDrawable(Color.rgb(0, 0, 0)));
                    break;

                case STS_PLAYFINISHED:
                    if (!playQueueList.isEmpty()) {
                        playQueueList.remove(0);

                        if (getSupportActionBar().getSelectedTab().getPosition()==QUEUE_TAB)
                        {
                            fillQueueListView();
                        }

                        if (!playQueueList.isEmpty()) {
                            File selectedFile = new File(playQueueList.get(0).getName());

                            if (D) Log.d(TAG, "Playing next " + selectedFile.getName());

                            mControlThread.sendFileName(selectedFile.getName());
                            mControlThread.sendFileSize(selectedFile.length());

                            if (mAcceptThread != null) {
                                mAcceptThread.cancel();
                                mAcceptThread = null;
                            }
                            if (mDataThread != null) {
                                mDataThread.cancel();
                                mDataThread = null;
                            }

                            mState = STATE_LISTEN;
                            mAcceptThread = new AcceptThread();
                            mAcceptThread.start();

                            mControlThread.sendBufferStart();
                            mControlThread.sendPlay();

                            // set the status for the listview pause button
                            playQueueList.get(0).play();
                        } else {
                            if (D) Log.d(TAG,"Nothing to play in queue");
                        }
                    } else {
                        if (D) Log.d(TAG,"Nothing in queue");
                    }
                    break;

                default:

            }
        }
    };

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;

            try {
                tmp = mBluetoothAdapter.listenUsingRfcommWithServiceRecord("BTstream", DATA_UUID);
            } catch (IOException e) { Log.e(TAG, "listen() failed", e);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN AcceptThread");

            BluetoothSocket socket;

            // Listen to the server socket if we're not connected
            while (mState != STATE_CONNECTED) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "accept() failed");
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (MainActivity.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                // Situation normal. Start the connected thread.
                                if (mAcceptThread != null) {
                                    mAcceptThread.cancel();
                                    mAcceptThread = null;
                                }


                                mDataThread = new DataThread(socket);
                                mDataThread.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
                                mDataThread.start();

                                mState =STATE_CONNECTED;

                                //Properties prop = new Properties();
                                //try {
                                //    prop.setProperty("lastUsedDeviceAddress", mDevice.getAddress());

                                //    prop.store(new FileOutputStream(getCacheDir().getAbsolutePath()+"btstream.properties"), null);
                                //} catch (IOException e) {Log.e(TAG,"Problem saving lastDevice property");}

                                //Toast.makeText(getApplicationContext(), "Connected to "+mDevice.getName(), Toast.LENGTH_SHORT).show();

                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                // Either not ready or already connected. Terminate new socket.
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket", e);
                                }
                                break;
                        }
                    }
                }
            }
        }

        public void cancel() {
            try {
                mmServerSocket.close();
            } catch (IOException e) {Log.e(TAG, "close() of server failed", e);}
        }
    }

    private class DataThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final OutputStream mmOutStream;
        private final InputStream mmInStream;

        private final int FILEBUFFER_SIZE=(int)1024*1024*8;

        boolean terminateRequested=false;

        File f;
        FileInputStream fin=null;
        byte[] fileContentPart=null;
        int bytesToSendPart=0;
        int bytesToSend=0;

        public DataThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn=null;
            OutputStream tmpOut=null;
            try {
                tmpIn=socket.getInputStream();
                tmpOut=socket.getOutputStream();
            } catch (IOException e) {}

            mmOutStream=tmpOut;
            mmInStream=tmpIn;

            f=new File(playQueueList.get(0).getName());
            bytesToSend = (int) f.length();

            fileContentPart=new byte[FILEBUFFER_SIZE];

            if (D) Log.d(TAG, "File size " + f.length() + " bytes (using " + fileContentPart.length + " chunks)");

        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN DataThread");

            try {
                fin = new FileInputStream(f.getAbsolutePath());


                while (bytesToSend > 0) {

                    if (terminateRequested) break;

                    int bytesRead = fin.read(fileContentPart, 0, fileContentPart.length);

                    try {

                        mmOutStream.write(fileContentPart, 0, bytesRead);

                    } catch (IOException e) {
                        Log.e(TAG, "Problem sending file " + e);
                    }

                    if (D) Log.d(TAG, "Sent " + bytesRead);

                    bytesToSend-=bytesRead;
                }

                fin.close();

            } catch (FileNotFoundException e) {
                Log.e(TAG, "File not found");
            } catch (IOException e) {
                Log.e(TAG, "Problem reading file");
            }
        }
/*
        public void sendComplete() {
            if (D) Log.d(TAG, "Sent " + offset);

            mControlThread.sendBufferEnd();

            bytesToSend=null;
            System.gc();
        }

        public void sendFile(byte[] b) {

            bytesToSend=Arrays.copyOf(b,b.length);
            offset=0;

            if (D) Log.d(TAG, "Starting send");
        }
*/
        public void cancel() {
            if (D) Log.d(TAG, "Closing Data socket");
            try {
                mmInStream.close();
                mmOutStream.close();

                mmSocket.close();

                terminateRequested=true;
            } catch (IOException e) {Log.d(TAG, "Problem cancelling DataThread");}
        }
    }


}
