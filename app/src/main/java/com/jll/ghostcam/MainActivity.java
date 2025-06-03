package com.jll.ghostcam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GhostCamActivity";
    private static final String PREFS_NAME = "GhostCamPrefs";
    private static final String VIDEO_URI_KEY = "selectedVideoUri";
    private static final String SELECTED_APP_KEY = "selectedAppPackage";

    // --- File Names for Xposed Hook Communication ---
    private static final String GHOSTCAM_VIDEO_FILENAME = "ghost.mp4"; // Video in target app's data
    private static final String CONTROL_FILES_DIR_NAME = "Camera1";   // Dir in DCIM & target app's data
    // Control files in DCIM/Camera1/
    private static final String DISABLE_GHOSTCAM_FILE = "disable.jpg";
    private static final String NO_SILENT_FILE = "no-silent.jpg";
    private static final String NO_TOAST_FILE = "no_toast.jpg";
    private static final String TARGET_APP_FILE = "target_app.txt"; // Stores the package name of the app to hook

    private ImageView logoImageView;
    private TextView descriptionTextView;
    private SwitchCompat switchWarning, switchDisableGhostCam, switchPlaySound;
    private Button selectVideoButton;
    private Spinner appSpinner;
    private Button exitButton;
    private Button createGhostCamButton;

    private Uri selectedVideoUri;
    private String selectedAppPackageName;
    private List<AppEntry> installedAppsList;
    private SharedPreferences sharedPreferences;

    private ActivityResultLauncher<String> requestStoragePermissionLauncher;
    private ActivityResultLauncher<Intent> pickVideoLauncher;
    private ActivityResultLauncher<Intent> requestManageAllFilesLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initializeUI();
        initializeResultLaunchers();
        setupListeners();

        loadPreferences();
        populateAppSpinner();

        checkAndRequestManageAllFilesAccessIfNeeded(); // Request first
    }

    private void initializeUI() {
        logoImageView = findViewById(R.id.logo);
        descriptionTextView = findViewById(R.id.description_text);
        switchWarning = findViewById(R.id.switch1);
        switchDisableGhostCam = findViewById(R.id.switch2);
        switchPlaySound = findViewById(R.id.switch3);
        selectVideoButton = findViewById(R.id.select_video_button);
        appSpinner = findViewById(R.id.app_spinner);
        exitButton = findViewById(R.id.exit_button);
        createGhostCamButton = findViewById(R.id.create_ghostcam_button);
    }

    private void initializeResultLaunchers() {
        requestStoragePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), isGranted -> {
                    if (isGranted) {
                        openVideoPicker();
                        if (createControlFilesDirectoryIfNeeded()) {
                            updateSwitchStatesFromFiles();
                        }
                    } else {
                        Toast.makeText(this, getString(R.string.permission_storage_needed_message), Toast.LENGTH_LONG).show();
                    }
                });

        pickVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri videoUri = result.getData().getData(); // Get the URI from the result
                        if (videoUri != null) {
                            selectedVideoUri = videoUri; // Assign to member variable
                            savePreferences(); // Save the new selected video URI
                            selectVideoButton.setText(getString(R.string.video_selected_toast, getFileNameFromUri(selectedVideoUri)));

                            // Copy video to DCIM/Camera1/ghost.mp4
                            try {
                                InputStream inputStream = getContentResolver().openInputStream(selectedVideoUri);
                                File ghostFile = new File(Environment.getExternalStorageDirectory(), "DCIM/Camera1/ghost.mp4");

                                // Make sure the folder exists
                                File dir = ghostFile.getParentFile();
                                if (dir != null && !dir.exists()) {
                                    dir.mkdirs();
                                }

                                OutputStream outputStream = new FileOutputStream(ghostFile);

                                byte[] buffer = new byte[4096];
                                int length;
                                while ((length = inputStream.read(buffer)) > 0) {
                                    outputStream.write(buffer, 0, length);
                                }

                                inputStream.close();
                                outputStream.close();

                                Toast.makeText(this, "Ghost video saved successfully!", Toast.LENGTH_SHORT).show();

                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(this, "Failed to copy video: " + e.getMessage(), Toast.LENGTH_SHORT).show(); // Added error message
                            }
                        }
                    }
                }
        );

        requestManageAllFilesLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if (Environment.isExternalStorageManager()) {
                            Toast.makeText(this, "All Files Access granted.", Toast.LENGTH_SHORT).show();
                            if (createControlFilesDirectoryIfNeeded()) {
                                updateSwitchStatesFromFiles();
                            }
                        } else {
                            Toast.makeText(this, "All Files Access not granted. Some features may be limited.", Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if (uri == null) return "Unknown Video";
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) {
                        result = cursor.getString(displayNameIndex);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting filename from content URI", e);
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        return result != null && !result.isEmpty() ? result : "Selected Video";
    }

    private void setupListeners() {
        selectVideoButton.setOnClickListener(v -> {
            if (checkAndRequestStoragePermissions()) {
                openVideoPicker();
            }
        });
        createGhostCamButton.setOnClickListener(v -> handleCreateGhostCam());
        exitButton.setOnClickListener(v -> finishAffinity());

        switchWarning.setOnCheckedChangeListener((buttonView, isChecked) -> {
            manageControlFile(getNoToastFile(), !isChecked); // Switch ON = File NOT exists
            savePreferences();
        });
        switchDisableGhostCam.setOnCheckedChangeListener((buttonView, isChecked) -> {
            manageControlFile(getDisableGhostCamFile(), isChecked); // Switch ON = File EXISTS
            savePreferences();
        });
        switchPlaySound.setOnCheckedChangeListener((buttonView, isChecked) -> {
            manageControlFile(getNoSilentFile(), isChecked);      // Switch ON = File EXISTS
            savePreferences();
        });

        appSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (installedAppsList != null && position > 0 && position - 1 < installedAppsList.size()) {
                    selectedAppPackageName = installedAppsList.get(position - 1).packageName;
                } else {
                    selectedAppPackageName = null;
                }
                savePreferences();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedAppPackageName = null;
                savePreferences();
            }
        });
    }

    private void loadPreferences() {
        String videoUriString = sharedPreferences.getString(VIDEO_URI_KEY, null);
        if (videoUriString != null) {
            selectedVideoUri = Uri.parse(videoUriString);
            boolean hasPermission = false;
            if (selectedVideoUri != null) {
                try (InputStream ignored = getContentResolver().openInputStream(selectedVideoUri)) {
                    hasPermission = true;
                } catch (Exception e) {
                    Log.w(TAG, "Lost permission or URI invalid on load: " + selectedVideoUri, e);
                    selectedVideoUri = null;
                }
            }
            if (hasPermission) {
                selectVideoButton.setText(getString(R.string.video_selected_toast, getFileNameFromUri(selectedVideoUri)));
            } else {
                selectVideoButton.setText(getString(R.string.select_video_button_text));
                selectedVideoUri = null; // Ensure it's null if permission lost
            }
        } else {
            selectVideoButton.setText(getString(R.string.select_video_button_text));
        }
        selectedAppPackageName = sharedPreferences.getString(SELECTED_APP_KEY, null);
    }

    private void savePreferences() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (selectedVideoUri != null) editor.putString(VIDEO_URI_KEY, selectedVideoUri.toString());
        else editor.remove(VIDEO_URI_KEY);
        if (selectedAppPackageName != null) editor.putString(SELECTED_APP_KEY, selectedAppPackageName);
        else editor.remove(SELECTED_APP_KEY);
        editor.apply();
    }

    private void populateAppSpinner() {
        installedAppsList = new ArrayList<>();
        PackageManager pm = getPackageManager();
        List<String> appNamesForSpinner = new ArrayList<>();
        appNamesForSpinner.add(getString(R.string.select_app_text)); // Use string resource

        List<ApplicationInfo> allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo appInfo : allApps) {
            boolean usesCamera = false;
            try {
                String[] requestedPermissions = pm.getPackageInfo(appInfo.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions;
                if (requestedPermissions != null) {
                    for (String permission : requestedPermissions) {
                        if (Manifest.permission.CAMERA.equals(permission)) {
                            usesCamera = true;
                            break;
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException e) { /* Ignore */ }

            if (usesCamera && pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                String appLabel = pm.getApplicationLabel(appInfo).toString();
                if (!appLabel.isEmpty() && !appInfo.packageName.isEmpty()) {
                    installedAppsList.add(new AppEntry(appLabel, appInfo.packageName));
                }
            }
        }

        Collections.sort(installedAppsList, Comparator.comparing(app -> app.appName.toLowerCase()));
        for (AppEntry entry : installedAppsList) {
            appNamesForSpinner.add(entry.appName);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, appNamesForSpinner);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        appSpinner.setAdapter(adapter);

        if (selectedAppPackageName != null) {
            for (int i = 0; i < installedAppsList.size(); i++) {
                if (installedAppsList.get(i).packageName.equals(selectedAppPackageName)) {
                    appSpinner.setSelection(i + 1); // +1 for hint
                    break;
                }
            }
        }
    }

    private void updateSwitchStatesFromFiles() {
        if (!hasStoragePermissionsForControlFiles()) {
            Log.w(TAG, "Cannot update switch states, missing storage permissions for control files.");
            return;
        }
        if (!createControlFilesDirectoryIfNeeded()){
            Log.w(TAG, "Cannot update switch states, control directory not available.");
            return;
        }
        switchWarning.setChecked(!getNoToastFile().exists());
        switchDisableGhostCam.setChecked(getDisableGhostCamFile().exists());
        switchPlaySound.setChecked(getNoSilentFile().exists());
    }

    private File getControlFilesBaseDir() {
        // Control files are always in DCIM/Camera1 for the Xposed hook to find them easily
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), CONTROL_FILES_DIR_NAME);
    }

    private boolean hasStoragePermissionsForControlFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private boolean createControlFilesDirectoryIfNeeded() {
        if (!hasStoragePermissionsForControlFiles()) {
            Log.w(TAG, "Cannot create control directory, missing storage permissions.");
            return false;
        }

        File controlDir = getControlFilesBaseDir();
        if (!controlDir.exists()) {
            if (controlDir.mkdirs()) {
                Log.d(TAG, "Control directory created: " + controlDir.getAbsolutePath());
                // Initialize default control files states
                manageControlFile(getNoToastFile(), !switchWarning.isChecked()); // Default: warning ON (no_toast NOT exists)
                manageControlFile(getDisableGhostCamFile(), switchDisableGhostCam.isChecked()); // Default: GhostCam ON (disable_ghostcam NOT exists)
                manageControlFile(getNoSilentFile(), switchPlaySound.isChecked());      // Default: Sound OFF (no-silent NOT exists)
                return true;
            } else {
                Log.e(TAG, "Failed to create control directory: " + controlDir.getAbsolutePath());
                return false;
            }
        }
        return true;
    }

    private File getDisableGhostCamFile() { return new File(getControlFilesBaseDir(), DISABLE_GHOSTCAM_FILE); }
    private File getNoSilentFile() { return new File(getControlFilesBaseDir(), NO_SILENT_FILE); }
    private File getNoToastFile() { return new File(getControlFilesBaseDir(), NO_TOAST_FILE); }
    private File getTargetAppFile() { return new File(getControlFilesBaseDir(), TARGET_APP_FILE); }

    private void manageControlFile(File file, boolean shouldExist) {
        if (!hasStoragePermissionsForControlFiles()) {
            Log.e(TAG, "Cannot manage control file " + file.getName() + ", missing storage permissions.");
            return;
        }
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!createControlFilesDirectoryIfNeeded()) { // Try to create if doesn't exist
                Log.e(TAG, "Cannot manage control file, parent directory " + parentDir.getAbsolutePath() + " could not be created.");
                return;
            }
        } else if (parentDir == null) {
            Log.e(TAG, "Cannot manage control file, parent directory is null for " + file.getAbsolutePath());
            return;
        }

        try {
            if (shouldExist) {
                if (!file.exists()) {
                    if (file.createNewFile()) {
                        Log.d(TAG, "Created control file: " + file.getAbsolutePath());
                    } else {
                        Log.e(TAG, "Failed to create control file: " + file.getAbsolutePath());
                    }
                }
            } else {
                if (file.exists()) {
                    if (file.delete()) {
                        Log.d(TAG, "Deleted control file: " + file.getAbsolutePath());
                    } else {
                        Log.e(TAG, "Failed to delete control file: " + file.getAbsolutePath());
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error managing control file " + file.getName(), e);
            Toast.makeText(this, getString(R.string.error_managing_control_files, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void writeTargetAppFile(String packageName) {
        if (!hasStoragePermissionsForControlFiles()) {
            Log.e(TAG, "Cannot write target_app.txt, missing storage permissions.");
            return;
        }
        if (!createControlFilesDirectoryIfNeeded()) {
            Log.e(TAG, "Cannot write target_app.txt, control directory not available.");
            return;
        }

        File targetFile = getTargetAppFile();
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(packageName.getBytes());
            Log.d(TAG, "Wrote target app '" + packageName + "' to: " + targetFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + TARGET_APP_FILE, e);
            Toast.makeText(this, "Error saving target app preference.", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleCreateGhostCam() {
        if (selectedVideoUri == null) { // This condition was failing
            Toast.makeText(this, getString(R.string.please_select_video_toast), Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedAppPackageName == null || selectedAppPackageName.isEmpty()) {
            Toast.makeText(this, getString(R.string.please_select_app_toast), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isRootAvailable()) {
            Toast.makeText(this, getString(R.string.root_access_required), Toast.LENGTH_LONG).show();
            return;
        }

        // Write target app to control file for Xposed hook to read
        writeTargetAppFile(selectedAppPackageName);

        // Path for ghost.mp4 inside the target app's internal data directory
        String internalDataPathBase = "/data/data/" + selectedAppPackageName;
        File targetDir = new File(internalDataPathBase + "/files/" + CONTROL_FILES_DIR_NAME);
        File targetVideoFile = new File(targetDir, GHOSTCAM_VIDEO_FILENAME);

        Log.d(TAG, "Target directory for video: " + targetDir.getAbsolutePath());
        Log.d(TAG, "Target video file: " + targetVideoFile.getAbsolutePath());

        new CopyVideoTask(this, selectedVideoUri, targetDir.getAbsolutePath(), targetVideoFile.getAbsolutePath(), selectedAppPackageName)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class CopyVideoTask extends AsyncTask<Void, Integer, String> {
        private final WeakReference<MainActivity> activityReference;
        private final Context applicationContext;
        private final Uri sourceUri;
        private final String targetDirectoryPath;
        private final String targetVideoPath;
        private final String targetAppPkgName;
        private Dialog progressDialog;

        CopyVideoTask(MainActivity activity, Uri sourceUri, String targetDirectoryPath, String targetVideoPath, String targetAppPkgName) {
            this.activityReference = new WeakReference<>(activity);
            this.applicationContext = activity.getApplicationContext();
            this.sourceUri = sourceUri;
            this.targetDirectoryPath = targetDirectoryPath;
            this.targetVideoPath = targetVideoPath;
            this.targetAppPkgName = targetAppPkgName;
        }

        @Override
        protected void onPreExecute() {
            MainActivity activity = activityReference.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setView(new ProgressBar(activity, null, android.R.attr.progressBarStyleLarge));
            builder.setMessage(activity.getString(R.string.creating_ghostcam_toast, targetAppPkgName));
            builder.setCancelable(false);
            progressDialog = builder.create();
            progressDialog.show();
        }

        @Override
        protected String doInBackground(Void... voids) {
            File tempVideoFile = new File(applicationContext.getCacheDir(), "temp_ghost_video.mp4");
            try (InputStream inputStream = applicationContext.getContentResolver().openInputStream(sourceUri);
                 OutputStream outputStream = new FileOutputStream(tempVideoFile)) {
                if (inputStream == null) return "Failed to open source video.";
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error copying video to cache", e);
                return "Error during local copy: " + e.getMessage();
            }

            try {
                executeRootCommand("mkdir -p \"" + targetDirectoryPath + "\"");
                executeRootCommand("rm -f \"" + targetVideoPath + "\""); // Remove old video
                executeRootCommand("cp \"" + tempVideoFile.getAbsolutePath() + "\" \"" + targetVideoPath + "\"");
                executeRootCommand("chmod 644 \"" + targetVideoPath + "\""); // Make world-readable
            } catch (IOException | InterruptedException e) {
                Log.e(TAG, "Error using root commands", e);
                Thread.currentThread().interrupt(); // Restore interrupted status
                return "Root command execution failed: " + e.getMessage();
            } finally {
                if (tempVideoFile.exists() && !tempVideoFile.delete()) {
                    Log.w(TAG, "Failed to delete temp video file: " + tempVideoFile.getAbsolutePath());
                }
            }
            return null; // Success
        }

        @Override
        protected void onPostExecute(String errorMessage) {
            MainActivity activity = activityReference.get();
            if (progressDialog != null && progressDialog.isShowing()) {
                try {
                    progressDialog.dismiss();
                } catch (Exception e) { /* ignore if activity is gone */ }
            }
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Log.w(TAG, "Activity gone. Result: " + (errorMessage == null ? "Success for " + targetAppPkgName : "Failed: " + errorMessage));
                return;
            }

            if (errorMessage == null) {
                Toast.makeText(activity, activity.getString(R.string.ghostcam_created_success, targetAppPkgName), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(activity, activity.getString(R.string.ghostcam_creation_failed, errorMessage), Toast.LENGTH_LONG).show();
            }
        }

        private void executeRootCommand(String command) throws IOException, InterruptedException {
            Log.d(TAG, "Executing root command: " + command);
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            new StreamGobbler(process.getInputStream(), "INFO").start();
            new StreamGobbler(process.getErrorStream(), "ERROR").start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Root command [" + command + "] failed with exit code " + exitCode);
            }
        }

        private static class StreamGobbler extends Thread {
            private final InputStream is;
            private final String type;
            StreamGobbler(InputStream is, String type) { this.is = is; this.type = type; }
            @Override
            public void run() {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.d(TAG, type + "> " + line);
                    }
                } catch (IOException ioe) { /* ignore */ }
            }
        }
    }

    private boolean checkAndRequestStoragePermissions() {
        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_VIDEO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE; // For picking video
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestStoragePermissionLauncher.launch(permission);
            return false;
        }
        // For control files on older Android, also check WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002); // Different request code
            return false;
        }
        return true;
    }

    private void checkAndRequestManageAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.all_files_access_needed_title))
                        .setMessage(getString(R.string.all_files_access_needed_message))
                        .setPositiveButton(getString(R.string.ok), (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                requestManageAllFilesLauncher.launch(intent);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to launch MANAGE_ALL_FILES_ACCESS settings", e);
                            }
                        }).setNegativeButton(getString(R.string.cancel), null).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) { // Fallback from MANAGE_ALL_FILES intent
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (createControlFilesDirectoryIfNeeded()) {
                    updateSwitchStatesFromFiles();
                }
            } else {
                Toast.makeText(this, "Storage permission for fallback denied.", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == 1002) { // For WRITE_EXTERNAL_STORAGE on pre-R
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (createControlFilesDirectoryIfNeeded()) {
                    updateSwitchStatesFromFiles();
                }
            } else {
                Toast.makeText(this, "Write storage permission denied for control files.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pickVideoLauncher.launch(intent);
    }

    // Removed the redundant onActivityResult method as ActivityResultLaunchers handle it.
    /*
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            Uri selectedVideoUri = data.getData();

            try {
                InputStream inputStream = getContentResolver().openInputStream(selectedVideoUri);
                File ghostVideoFile = new File(Environment.getExternalStorageDirectory(), "DCIM/Camera1/ghost.mp4");

                File dir = ghostVideoFile.getParentFile();
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                OutputStream outputStream = new FileOutputStream(ghostVideoFile);

                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }

                inputStream.close();
                outputStream.close();

                Toast.makeText(this, "GhostCam video saved!", Toast.LENGTH_SHORT).show();

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Failed to save video", Toast.LENGTH_SHORT).show();
            }
        }
    }
    */

    private boolean isRootAvailable() {
        for (String pathDir : System.getenv("PATH").split(File.pathSeparator)) {
            if (new File(pathDir, "su").exists()) return true;
        }
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/system/xbin/which", "su"});
            return process.waitFor() == 0;
        } catch (Exception e) { /* ignore */ }
        return false;
    }

    private static class AppEntry {
        final String appName;
        final String packageName;
        AppEntry(String appName, String packageName) { this.appName = appName; this.packageName = packageName; }
        @NonNull @Override public String toString() { return appName; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasStoragePermissionsForControlFiles()) {
            if (createControlFilesDirectoryIfNeeded()) {
                updateSwitchStatesFromFiles();
            }
        } else {
            Log.w(TAG, "onResume: Missing storage permissions for control files. Switches might not be accurate yet.");
        }
    }
}