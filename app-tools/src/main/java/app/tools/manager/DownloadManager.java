package app.tools.manager;

import android.app.Activity;
import android.app.Application;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class DownloadManager extends Thread {
    private static final String TAG = "DownloadManager";

    private final static int START_DOWNLOADING = 1;
    private final static int PROGRESS_UPDATE = 2;
    private final static int DOWNLOAD_FINISH = 3;
    private final static int ERROR = 4;
    private final static int NO_INTERNET_CONNECTION = 5;
    private final static int RESOURCES_READY = 6;

    private List<Downloader> downloaderList = new ArrayList<>();
    private HttpURLConnection httpURLConnection;
    private DownloadListener downloadListener;
    private ProgressDialog progressDialog;
    private Activity currentActivity;
    private InputStream inputStream;
    private String dialogTitle;
    private Context context;

    private boolean cancelOnPause = false;
    private boolean displayDialog = false;
    private boolean horizontalStyle = true;
    private boolean canceled = false;

    public DownloadManager(Context context) {
        setActivityLifeCycle(context);
        new ErrorMessage(context);
        this.context = context;
    }

    public void download(Downloader downloader) {
        this.downloaderList.add(downloader);
    }

    @Override
    public void run() {
        Message message;
        boolean error = false;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            if (!isAvailabeInternetConnection()) {
                message = handler.obtainMessage(NO_INTERNET_CONNECTION);
                handler.sendMessage(message);
                return;
            } else {
                System.setProperty("http.keepAlive", "false");
            }

            /*
            * onPreExecute
            * */
            message = handler.obtainMessage(START_DOWNLOADING);
            handler.sendMessage(message);

            for (int i = 0; i < downloaderList.size(); i++) {
                if (isCanceled()) {
                    break;
                }
                Downloader downloader = downloaderList.get(i);
                downloader.init(context);
                /*
                * onUpdateProgress
                * */
                message = handler.obtainMessage(PROGRESS_UPDATE, i, downloaderList.size(), downloader);
                handler.sendMessage(message);

                startDownloading(downloader);
            }
        } catch (Exception e) {
            if (!isCanceled()) {
                /*
                * onError
                * */
                String errorMessage = e.getMessage() == null ? "" : e.getMessage();
                if (!ErrorMessage.containsError(errorMessage)) {
                    errorMessage = ErrorMessage.DEFAULT_CONNECTION_ERROR_MESSAGE;
                }
                message = handler.obtainMessage(ERROR, errorMessage);
                handler.sendMessage(message);
                error = true;
            }
            e.printStackTrace();
        } finally {
            unregisterActivityLifeCycle();
            if (!isCanceled()) {
                disconnect();

                /*
                * onPostExecute
                * */
                if (!error) {
                    message = handler.obtainMessage(DOWNLOAD_FINISH);
                    handler.sendMessage(message);
                }
            }
        }
    }

    private void onNoInternetConnection() {
        Log.e(TAG, "********NO INTERNET CONNECTION******");
        if (downloadListener != null)
            downloadListener.onNoInternetConnection();
    }

    private void onPreExecute() {
        Log.e(TAG, "**************STARTED***************");
        if (displayDialog) {
            onShowProgressDialog();
        }
        if (downloadListener != null)
            downloadListener.onStart();
    }

    private void onPostExecute() {
        Log.e(TAG, "***********FINISH SUCCESS***********");
        dissmissProgressDialog();
        if (downloadListener != null)
            downloadListener.onFinishSuccess();
    }

    private void onResourcesReady(Object object) {
        Log.e(TAG, "***********ON DOWNLOAD SUCCESS***********");
        DownloadResult downloadResult = (DownloadResult) object;
        Downloader downloader = downloadResult.getDownloader();
        Object resource = downloadResult.getResult();
        downloader.onResult(resource);
    }

    private void onProgressUpdate(int progress, int size, Downloader downloader) {
        String simpleName = downloader.getClass().getSimpleName();
        Log.e(TAG, "**********CURRENT TASK: " + simpleName + "************");
        if (downloadListener != null)
            downloadListener.onPublishProgress(progress, size, downloader);
        if (progressDialog != null && horizontalStyle) {
            progressDialog.setMessage("Aktualnie pobiera: " + simpleName);
            progressDialog.setMax(size);
            progressDialog.setProgress(progress);
        }
    }

    private void onError(String errorMessage) {
        Log.e(TAG, "***************ERROR**************");
        dissmissProgressDialog();
        if (downloadListener != null)
            downloadListener.onError(errorMessage);
    }

    public void onCancelled() {
        Log.e(TAG, "************CANCELLED*************");
        setCanceled(true);
        dissmissProgressDialog();
        interrupt();
        disconnect();
        if (downloadListener != null)
            downloadListener.onCancelled();
    }

    private void disconnect() {
        Log.e(TAG, "************DISCONNECT************");
        try {
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }
            if (httpURLConnection != null) {
                httpURLConnection.disconnect();
                httpURLConnection = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void unregisterActivityLifeCycle() {
        if (currentActivity != null) {
            currentActivity.getApplication().unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks);
        }
    }

    public boolean isAvailabeInternetConnection() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnectedOrConnecting();
    }

    private void startDownloading(Downloader downloader) throws Exception {
        int retryCount = downloader.onCreateRetryCount();

        String requestMethod = downloader.onCreateRequestMethod();
        RequestParams requestParams = new RequestParams();
        downloader.onCreateRequestParams(requestParams);

        retryCount = retryCount > 5 || retryCount < 1 ? 5 : retryCount;
        requestMethod = requestMethod == null ? RequestMethod.POST : requestMethod;
        String requestUrl = downloader.onCreateUrl();
        HashMap<String, String> urlParams = requestParams.getUrlParams();

        Uri.Builder uriBuilder = new Uri.Builder();
        for (String key : urlParams.keySet()) {
            String value = urlParams.get(key);
            uriBuilder.appendQueryParameter(key, value);
        }
        String params = uriBuilder.build().getEncodedQuery();

        for (int i = retryCount; i > 0; i--) {
            if (isCanceled()) {
                break;
            }
            String response = onDownloading(requestUrl, params, requestMethod);
            if (response != null) {
                Log.e(TAG, response);
                Object object = downloader.onDownloadSuccess(response);
                DownloadResult downloadResult = new DownloadResult();
                downloadResult.setDownloader(downloader);
                downloadResult.setResult(object);

                Message message = handler.obtainMessage(RESOURCES_READY, downloadResult);
                handler.sendMessage(message);
                break;
            }

            if (i == 1) {
                throw new Exception();
            }

            if (!isCanceled()) {
                Log.e(TAG, "******RETRY COUNT: " + (i - 1) + "*******");
                sleep(5000);
            }
        }
    }

    protected String onDownloading(String requestUrl, String params, String requestMethod) {
        String response = null;
        try {
            if (requestMethod.equals(RequestMethod.POST)) {
                URL postUrl = new URL(requestUrl);
                httpURLConnection = (HttpURLConnection) postUrl.openConnection();
                httpURLConnection.setRequestMethod(RequestMethod.POST);
                if (!TextUtils.isEmpty(params)) {
                    OutputStream outputStream = httpURLConnection.getOutputStream();
                    BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"));
                    bufferedWriter.write(params);
                    bufferedWriter.flush();
                    bufferedWriter.close();
                    outputStream.close();
                }
            } else if (requestMethod.equals(RequestMethod.GET)) {
                URL getUrl = new URL(requestUrl + "?" + params);
                httpURLConnection = (HttpURLConnection) getUrl.openConnection();
                httpURLConnection.setRequestMethod(RequestMethod.GET);
            } else {
                throw new Exception();
            }

            int responseCode = httpURLConnection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                inputStream = httpURLConnection.getInputStream();

                response = getHttpConnectionResult(inputStream);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return response;
    }

    protected String getHttpConnectionResult(InputStream inputStream) throws Exception {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder stringBuilder = new StringBuilder();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            stringBuilder.append(line);
            if (isCanceled()) {
                break;
            }
        }
        return stringBuilder.toString();
    }

    private final Handler handler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            switch (message.what) {
                case START_DOWNLOADING:
                    onPreExecute();
                    break;
                case PROGRESS_UPDATE:
                    Downloader downloader = (Downloader) message.obj;
                    int progress = message.arg1;
                    int size = message.arg2;
                    onProgressUpdate(progress, size, downloader);
                    break;
                case ERROR:
                    String errorMessage = (String) message.obj;
                    onError(errorMessage);
                    break;
                case DOWNLOAD_FINISH:
                    onPostExecute();
                    break;
                case NO_INTERNET_CONNECTION:
                    onNoInternetConnection();
                    break;
                case RESOURCES_READY:
                    Object object = message.obj;
                    onResourcesReady(object);
                    break;
                default:
                    break;
            }
            return true;
        }
    });

    public void showProgressDialog(String title, boolean horizontalStyle) {
        setDisplayDialog(true);
        setDialogTitle(title);
        setHorizontalStyle(horizontalStyle);
    }

    private void onShowProgressDialog() {
        try {
            progressDialog = new ProgressDialog(context);
            if (horizontalStyle)
                progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);
            progressDialog.setTitle(getDialogTitle());
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
                boolean onKeyPressed = false;

                @Override
                public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && !onKeyPressed) {
                        this.onKeyPressed = true;
                        onCancelled();
                    }
                    return true;
                }
            });
            progressDialog.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void dissmissProgressDialog() {
        try {
            if (progressDialog != null)
                progressDialog.dismiss();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setDownloadListener(DownloadListener downloadListener) {
        this.downloadListener = downloadListener;
    }

    public void setDisplayDialog(boolean displayDialog) {
        this.displayDialog = displayDialog;
    }

    public void setDialogTitle(String dialogTitle) {
        this.dialogTitle = dialogTitle;
    }

    public void setHorizontalStyle(boolean horizontalStyle) {
        this.horizontalStyle = horizontalStyle;
    }

    public String getDialogTitle() {
        return dialogTitle;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCancelOnPause(boolean cancelOnPause) {
        this.cancelOnPause = cancelOnPause;
    }

    public boolean isRunning() {
        State state = getState();
        Log.e(TAG, state.name());
        return state == State.RUNNABLE;
    }

    private void setActivityLifeCycle(Context context) {
        if (context instanceof Activity) {
            Activity currentActivity = (Activity) context;
            setActivity(currentActivity);
            currentActivity.getApplication().registerActivityLifecycleCallbacks(activityLifecycleCallbacks);
        }
    }

    private final Application.ActivityLifecycleCallbacks activityLifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {

        }

        @Override
        public void onActivityStarted(Activity activity) {

        }

        @Override
        public void onActivityResumed(Activity activity) {

        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (currentActivity != null && currentActivity == activity && cancelOnPause) {
                onCancelled();
            }
        }

        @Override
        public void onActivityStopped(Activity activity) {

        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            if (currentActivity != null && currentActivity == activity) {
                downloadListener = null;
            }
        }
    };

    public void setActivity(Activity activity) {
        this.currentActivity = activity;
    }

}
