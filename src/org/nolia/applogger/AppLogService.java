package org.nolia.applogger;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.nolia.applogger.R;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.util.Log;

public class AppLogService extends Service {
	
	public static final int CODE_SAVE_LOG = 0x100;
	public static final int CODE_START = 0x001;
	public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(
			"dd-MM-yyyy-HHmm",
			Locale.getDefault());
	
	public static final String APP_LOG_SERVICE = "AppLogService";
	public static final String TAG = APP_LOG_SERVICE;
	
	private static final String APPS_LOG_DIR = "Appslog";
	private static final int CODE_INIT_FILES = 0x200;
	private static final int CODE_WATCH = 0x010;

	private static boolean created = false;
	
	public static boolean isCreated(){
		return created;
	}
	
	public List<String> getRunning(){
		List<String> result = new ArrayList<String>();
		List<RunningAppProcessInfo> runningAppProcesses = am.getRunningAppProcesses();
		for (RunningAppProcessInfo info : runningAppProcesses){
			// if package is used as process name and is launchable - then user started it. 
			final boolean visibleApp = (null != pm.getLaunchIntentForPackage(info.processName) );
			final boolean foreground = (info.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
			if (foreground && visibleApp){
				result.add(info.processName);
			}
		}
		return result;		
	}
	
	
	/**This service does not support binding. */
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
	
	@Override
	public void onCreate() {
		super.onCreate();
		created = true;
		am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
		pm = getPackageManager();
		initThreads();
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// preventing from being killed
		startForeground( 1, createNotification(true) );
		// initiate files
		logHandler.sendEmptyMessage(CODE_INIT_FILES);

		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		stopForeground(true);
		closeLogOutStream();
		stopThreads();
		created = false;
		super.onDestroy();
	}

	private void initThreads() {
		watchThread = new HandlerThread("WatchThread", Process.THREAD_PRIORITY_DEFAULT);
		watchThread.start();
		watchHandler = new Handler(watchThread.getLooper(), watchCallback);
		
		fileThread = new HandlerThread("LogFileThread", Process.THREAD_PRIORITY_BACKGROUND);
		fileThread.start();
		logHandler = new Handler(fileThread.getLooper(), logCallback);
	}
	
	// Saving to log logic
	private Handler.Callback logCallback = new Handler.Callback() {
		@Override
		public boolean handleMessage(Message msg) {
			switch (msg.what) {
			case CODE_SAVE_LOG:
				saveLog(msg.obj);
				return true;
			case CODE_INIT_FILES:
				final boolean fileOk = initFile();
				if (fileOk){
					// start watching apps
					watchHandler.removeCallbacksAndMessages(null);
					watchHandler.sendEmptyMessage(CODE_START);
				} else {
					Log.e(TAG, "Error initiatng files - exiting.");
					stopSelf();
				}
				return true;
			default:
				return false;
			}
		}
		
		private void saveLog(Object logMsg) {
			logOut.println(logMsg);
			logOut.flush();
		}
	};
	
	// Satch and process logic
	private Handler.Callback watchCallback = new Handler.Callback() {
		@Override
		public boolean handleMessage(Message msg) {
			switch (msg.what) {
			case CODE_WATCH:
				processApps();
				postNextWatch();
				return true;
				
			case CODE_START:
				// scan running apps at first time
				for (String app : getRunning()){
					activeApps.put(app, AppLogService.TIME_UNKNOWN);
				}
				postNextWatch();
				return true;

			default:
				return false;
			}
		}

		private void postNextWatch() {
			if (watchHandler != null){
				watchHandler.sendEmptyMessageDelayed(CODE_WATCH, 500);
			}
		}
	};
	
	// Helper methods
	private boolean initFile() {
		try {
			final boolean extStorageAccessible = isExternalStorageWritable();
			logFile = (extStorageAccessible) ?
					createLogFile()
				:	createLocalFile();
			logOut = new PrintWriter(logFile);
			Log.d(TAG, "Start logging to file " + logFile.getAbsolutePath() );
			logOut.println("Starting log: \n");
		} catch (IOException e) {
			Log.e(TAG, "Error creating file.", e);
			return false;
		}
		return true;
	}
	
	private void closeLogOutStream() {
		if (logOut == null){
			return;
		}
		final String endString = String.format(
				"------------\nEnd logging at %s .",
				AppLogService.SDF.format(new Date()));
		Log.d(TAG, endString);
		logOut.println(endString);
		logOut.flush();
		logOut.close();
	}

	private File createLocalFile(){
		return new File( getFilesDir(), getLogFileName() );
	}
	
	private File createLogFile() throws IOException {
		File logsDir = new File(
				Environment.getExternalStorageDirectory().getAbsolutePath()
				+ File.separatorChar
				+ APPS_LOG_DIR
				);
		if (!logsDir.exists()){
			if (!logsDir.mkdirs() ){
				throw new IOException("Log dir is not created.");
			}
		}
		final String fileName = getLogFileName();
		File logFile = new File(logsDir, fileName);
		logFile.createNewFile();
		return logFile;
		
	}
	
	// Workaround for starting service in foreground
	@SuppressWarnings("deprecation")
	private Notification createNotification(boolean show) {
		Notification notification = new Notification();
		final Intent mainActivity = new Intent(this, MainActivity.class);
		mainActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, mainActivity , 0);
		notification.icon = R.drawable.ic_launcher;
		if (show){
			notification.when = System.currentTimeMillis() + 100;
		}
		notification.setLatestEventInfo(this, "Logging apps", "Apps started to be logged.", contentIntent);
		return notification;
	}

	private String getLogFileName() {
		String timeStamp = DATE_FORMAT.format( new Date() );
		final String fileName = "log-" + timeStamp + ".txt";
		return fileName;
	}
	
	private boolean isExternalStorageWritable() {
		final String state = Environment.getExternalStorageState();
		return ( Environment.MEDIA_MOUNTED.equals(state) );
	}
	
	// Formatting constants, fields and methods for output
	private static final String OUT_FORMAT = "%30s %20s %20s";
	private static final long TIME_UNKNOWN = -1;
	private static final SimpleDateFormat SDF = new SimpleDateFormat(
			"HH:mm:ss dd-MM-yyyy", Locale.getDefault());
	
	private String timeToString(long time) {
		return (time == AppLogService.TIME_UNKNOWN) ? 
				" - "
			  : AppLogService.SDF.format(new Date(time));
	}
	
	private String appStateToString(String app, long startTime, long stopTime) {
		return String.format(OUT_FORMAT, app, timeToString(startTime),
				timeToString(stopTime));
	}
	
	// Main scanning logic 
	private void processApps(){
		long time = System.currentTimeMillis();
		final Set<String> lastApps = activeApps.keySet();
		final List<String> current = getRunning();
		Set<String> toRemove = new HashSet<String>();
		for (String app : lastApps) {
			if (current.contains(app)) {
				current.remove(app);
			} else {
				// saving state
				final long startTime = activeApps.get(app);
				String appLogInfo = appStateToString(app, startTime, time); 
				final Message msg = logHandler.obtainMessage(CODE_SAVE_LOG, appLogInfo );
				logHandler.sendMessage(msg);
				Log.d(TAG, appLogInfo.toString() );
				toRemove.add(app);
			}
		}
		for (String app : toRemove) {
			activeApps.remove(app);	
		}
		for (String newApp : current) {
			activeApps.put(newApp, time);
		}
	}

	private void stopThreads() {
		Thread stoppingThread = watchThread;
		watchHandler.removeCallbacksAndMessages(null);
		stoppingThread.interrupt();
		watchThread = null;
		watchHandler = null;
		
		stoppingThread = fileThread;
		logHandler.removeCallbacksAndMessages(null);
		stoppingThread.interrupt();
		fileThread = null;
		logHandler = null;
	}
	
	private Map<String, Long> activeApps = new HashMap<String, Long>();

	private ActivityManager am;
	private PackageManager pm;
	
	private HandlerThread fileThread;
	private Handler logHandler;

	private File logFile;
	private PrintWriter logOut;
	
	private Handler watchHandler;
	private HandlerThread watchThread;
	

}
