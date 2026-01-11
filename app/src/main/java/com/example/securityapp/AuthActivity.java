package com.example.securityapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class AuthActivity extends AppCompatActivity {
    private static final String TAG = "AuthActivity";

    // UI components
    private EditText emailEditText;
    private EditText passwordEditText;
    private EditText nameEditText;
    private Button loginButton;
    private Button registerButton;
    private SignInButton googleSignInButton;
    private ProgressBar progressBar;
    private TextView toggleModeText;
    private TextView authTitleText;
    private TextView usernameHelpText;
    private TextView forgotPasswordText;

    // Firebase Auth
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    // Google Sign In
    private GoogleSignInClient googleSignInClient;
    private boolean isLoginMode = true;

    private ActivityResultLauncher<Intent> googleSignInLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        try {
            // Initialize Firebase Auth with error handling
            initializeFirebaseAuth();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Firebase Auth in AuthActivity", e);
            // Continue without Firebase (fallback mode)
            mAuth = null;
            db = null;
        }

        // Initialize UI components
        emailEditText = findViewById(R.id.emailEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        nameEditText = findViewById(R.id.nameEditText);
        loginButton = findViewById(R.id.loginButton);
        registerButton = findViewById(R.id.registerButton);
        googleSignInButton = findViewById(R.id.googleSignInButton);
        progressBar = findViewById(R.id.progressBar);
        toggleModeText = findViewById(R.id.toggleModeText);
        authTitleText = findViewById(R.id.authTitleText);
        usernameHelpText = findViewById(R.id.usernameHelpText);
        forgotPasswordText = findViewById(R.id.forgotPasswordText);

        // Set up Google Sign In - Keep button visible even if configuration fails
        setupGoogleSignIn();

        // Set up Activity Result Launcher for Google Sign-In
        googleSignInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                        handleSignInResult(task);
                    } else if (result.getResultCode() == RESULT_CANCELED) {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "Google Sign-In canceled", Toast.LENGTH_SHORT).show();
                    } else {
                        progressBar.setVisibility(View.GONE);
                        if (result.getResultCode() == 0) {
                            Toast.makeText(this, "Google Sign-In configuration error. Please use email/password sign-in.", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "Google Sign-In failed. Please try email/password sign-in.", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        // Set up button click listeners
        loginButton.setOnClickListener(v -> loginUser());
        registerButton.setOnClickListener(v -> registerUser());
        googleSignInButton.setOnClickListener(v -> signInWithGoogle());
        toggleModeText.setOnClickListener(v -> toggleAuthMode());
        forgotPasswordText.setOnClickListener(v -> showForgotPasswordDialog());

        // Setup password visibility toggle
        setupPasswordToggle();

        // Initially show login mode
        updateUI();
    }

    /**
     * Initialize Firebase Auth with Google Services checking
     */
    private void initializeFirebaseAuth() {
        try {
            // Check Google Play Services availability first
            com.google.android.gms.common.GoogleApiAvailability apiAvailability =
                com.google.android.gms.common.GoogleApiAvailability.getInstance();
            int resultCode = apiAvailability.isGooglePlayServicesAvailable(this);

            if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                Log.w(TAG, "Google Play Services not available in AuthActivity: " + resultCode);

                // Disable Google Sign-In button
                if (googleSignInButton != null) {
                    googleSignInButton.setEnabled(false);
                    googleSignInButton.setVisibility(View.GONE);
                }

                // Initialize Firebase in limited mode
                try {
                    mAuth = FirebaseAuth.getInstance();
                    db = FirebaseFirestore.getInstance();
                    Log.d(TAG, "Firebase initialized in limited mode");
                } catch (Exception e) {
                    Log.e(TAG, "Firebase initialization failed, using fallback mode", e);
                    mAuth = null;
                    db = null;
                }

                // Show user that Google Sign-In is not available
                Toast.makeText(this, "Google Sign-In unavailable. Please use email/password.", Toast.LENGTH_LONG).show();
                return;
            }

            // Google Play Services available, initialize normally
            mAuth = FirebaseAuth.getInstance();
            db = FirebaseFirestore.getInstance();
            Log.d(TAG, "Firebase Auth initialized successfully with Google Services");

        } catch (Exception e) {
            Log.e(TAG, "Error in Firebase Auth initialization", e);
            mAuth = null;
            db = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Check if user is already signed in with fallback support
        if (isUserAuthenticated()) {
            // User is already authenticated, proceed to main activity
            proceedToMainActivity(getCurrentAuthenticatedUser());
        }
    }

    /**
     * Check if user is authenticated with fallback support
     */
    private boolean isUserAuthenticated() {
        try {
            // Try Firebase Auth first if available
            if (mAuth != null) {
                FirebaseUser currentUser = mAuth.getCurrentUser();
                if (currentUser != null) {
                    // For email users, check email verification
                    if (currentUser.getProviderData().size() > 1 &&
                        "password".equals(currentUser.getProviderData().get(1).getProviderId()) &&
                        !currentUser.isEmailVerified()) {
                        // Email user is not verified
                        showEmailVerificationRequiredDialog(currentUser.getEmail());
                        return false;
                    }
                    return true;
                }
            }

            // Fallback: check SharedPreferences for previous authentication
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            String userEmail = prefs.getString("user_email", "");
            return !userEmail.isEmpty();

        } catch (Exception e) {
            Log.e(TAG, "Error checking authentication status", e);
            return false;
        }
    }

    /**
     * Get current authenticated user (Firebase or fallback)
     */
    private FirebaseUser getCurrentAuthenticatedUser() {
        if (mAuth != null) {
            return mAuth.getCurrentUser();
        }
        return null; // Will be handled in proceedToMainActivity
    }

    private void toggleAuthMode() {
        isLoginMode = !isLoginMode;
        updateUI();
    }

    private void updateUI() {
        if (isLoginMode) {
            // Login mode
            authTitleText.setText("Welcome Back");
            findViewById(R.id.nameInputLayout).setVisibility(View.GONE);
            usernameHelpText.setVisibility(View.GONE);
            loginButton.setVisibility(View.VISIBLE);
            registerButton.setVisibility(View.GONE);
            toggleModeText.setText("Don't have an account? Sign up");

            // Show forgot password link in login mode
            if (forgotPasswordText != null) {
                forgotPasswordText.setVisibility(View.VISIBLE);
            }

            // Update subtitle for login
            TextView subtitleText = findViewById(R.id.authSubtitleText);
            if (subtitleText != null) {
                subtitleText.setText("Sign in to continue");
            }
        } else {
            // Register mode
            authTitleText.setText("Create Account");
            findViewById(R.id.nameInputLayout).setVisibility(View.VISIBLE);
            usernameHelpText.setVisibility(View.VISIBLE);
            loginButton.setVisibility(View.GONE);
            registerButton.setVisibility(View.VISIBLE);
            toggleModeText.setText("Already have an account? Sign in");

            // Hide forgot password link in register mode
            if (forgotPasswordText != null) {
                forgotPasswordText.setVisibility(View.GONE);
            }

            // Update subtitle for registration
            TextView subtitleText = findViewById(R.id.authSubtitleText);
            if (subtitleText != null) {
                subtitleText.setText("Join our security community");
            }
        }
    }

    private void loginUser() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();

        // Validate inputs
        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Email is required");
            return;
        }

        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("Password is required");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);

        // Try Firebase Auth if available, otherwise use fallback
        if (mAuth != null) {
            // Sign in with Firebase
            mAuth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                        @Override
                        public void onComplete(@NonNull Task<AuthResult> task) {
                            progressBar.setVisibility(View.GONE);
                            if (task.isSuccessful()) {
                                Log.d(TAG, "signInWithEmail:success");
                                FirebaseUser user = mAuth.getCurrentUser();

                                // Check if email is verified
                                if (user != null && !user.isEmailVerified()) {
                                    showEmailVerificationRequiredDialog(email);
                                } else {
                                    Toast.makeText(AuthActivity.this, "Login successful", Toast.LENGTH_SHORT).show();
                                    proceedToMainActivity(user);
                                }
                            } else {
                                Log.w(TAG, "signInWithEmail:failure", task.getException());
                                Toast.makeText(AuthActivity.this, "Authentication failed: " +
                                        task.getException().getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
        } else {
            // Fallback authentication using SharedPreferences
            // This is a simplified fallback - in production you'd want more secure validation
            Log.d(TAG, "Using fallback authentication");

            // Save user credentials for fallback mode
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            prefs.edit()
                .putString("user_email", email)
                .putString("user_name", email.substring(0, email.indexOf("@"))) // Simple username from email
                .putBoolean("firebase_unavailable", true)
                .apply();

            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, "Signed in with limited features", Toast.LENGTH_SHORT).show();
            proceedToMainActivity(null); // null indicates fallback mode
        }
    }

    /**
     * Show a dialog informing the user that email verification is required
     */
    private void showEmailVerificationRequiredDialog(String email) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Email Verification Required");
        builder.setMessage("Your email address (" + email + ") has not been verified. " +
                "Please check your inbox for the verification link.\n\n" +
                "Would you like us to send a new verification email?");

        builder.setPositiveButton("Send Verification Email", (dialog, which) -> {
            sendVerificationEmail();
        });

        builder.setNegativeButton("Close", null);

        // Add sign out button
        builder.setNeutralButton("Sign Out", (dialog, which) -> {
            mAuth.signOut();
            Toast.makeText(AuthActivity.this, "Signed out", Toast.LENGTH_SHORT).show();
        });

        builder.setCancelable(false);  // User must take action
        builder.show();
    }

    /**
     * Send a verification email to the current user
     */
    private void sendVerificationEmail() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "No user is currently signed in", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        user.sendEmailVerification()
                .addOnCompleteListener(task -> {
                    progressBar.setVisibility(View.GONE);
                    if (task.isSuccessful()) {
                        Toast.makeText(this, "Verification email sent to " + user.getEmail(),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Failed to send verification email: " +
                                task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void registerUser() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();
        String name = nameEditText.getText().toString().trim();

        // Validate inputs
        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Email is required");
            return;
        }

        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("Password is required");
            return;
        }

        if (TextUtils.isEmpty(name)) {
            nameEditText.setError("Name is required");
            return;
        }

        if (password.length() < 6) {
            passwordEditText.setError("Password must be at least 6 characters");
            return;
        }

        // Validate username format
        if (!isValidUsername(name)) {
            nameEditText.setError("Username can only contain letters, numbers, dots, and underscores");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);

        // Try Firebase registration if available, otherwise use fallback
        if (mAuth != null && db != null) {
            // First check if username is available
            checkUsernameAvailability(name, (isAvailable, suggestedName) -> {
                if (!isAvailable) {
                    progressBar.setVisibility(View.GONE);
                    nameEditText.setError("Username '" + name + "' is already taken");

                    if (suggestedName != null) {
                        showUsernameTakenDialog(name, suggestedName, email, password);
                    }
                    return;
                }

                // Username is available, proceed with registration
                createUserWithEmailAndPassword(email, password, name);
            });
        } else {
            // Fallback registration using SharedPreferences
            Log.d(TAG, "Using fallback registration");

            // Save user data for fallback mode
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            prefs.edit()
                .putString("user_email", email)
                .putString("user_name", name)
                .putBoolean("firebase_unavailable", true)
                .putLong("user_created_timestamp", System.currentTimeMillis())
                .apply();

            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, "Account created with limited features", Toast.LENGTH_SHORT).show();

            // Switch to login mode
            isLoginMode = true;
            updateUI();
            emailEditText.setText(email);
            passwordEditText.setText("");
        }
    }

    /**
     * Show dialog after sending verification email
     */
    private void showEmailVerificationSentDialog(String email) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Verify Your Email");
        builder.setMessage("A verification email has been sent to " + email + "\n\n" +
                "Please check your inbox and follow the verification link before signing in.\n\n" +
                "You may close this app and return after verifying your email.");

        builder.setPositiveButton("OK", (dialog, which) -> {
            // Switch to login mode and clear fields
            isLoginMode = true;
            updateUI();
            emailEditText.setText(email);
            passwordEditText.setText("");
        });

        builder.setNeutralButton("Resend Email", (dialog, which) -> {
            sendVerificationEmail();
        });

        builder.setCancelable(false);
        builder.show();
    }

    /**
     * Show dialog when username is taken with suggestions
     */
    private void showUsernameTakenDialog(String originalName, String suggestedName, String email, String password) {
        new AlertDialog.Builder(this)
            .setTitle("Username Not Available")
            .setMessage("The username '" + originalName + "' is already taken.\n\nWould you like to use '" + suggestedName + "' instead?")
            .setPositiveButton("Use " + suggestedName, (dialog, which) -> {
                nameEditText.setText(suggestedName);
                progressBar.setVisibility(View.VISIBLE);
                createUserWithEmailAndPassword(email, password, suggestedName);
            })
            .setNegativeButton("Choose Different", (dialog, which) -> {
                nameEditText.requestFocus();
                nameEditText.selectAll();
            })
            .show();
    }

    /**
     * Validate username format
     */
    private boolean isValidUsername(String username) {
        if (username == null || username.length() < 3 || username.length() > 30) {
            return false;
        }

        // Allow letters, numbers, dots, and underscores
        return username.matches("^[a-zA-Z0-9._]+$");
    }

    /**
     * Check if username is available in database
     */
    private void checkUsernameAvailability(String username, UsernameCheckCallback callback) {
        if (db == null) {
            callback.onResult(true, null);
            return;
        }

        // Convert to lowercase for case-insensitive comparison
        String normalizedUsername = username.toLowerCase();

        db.collection("users")
            .whereEqualTo("username_normalized", normalizedUsername)
            .get()
            .addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    QuerySnapshot querySnapshot = task.getResult();

                    if (querySnapshot != null && !querySnapshot.isEmpty()) {
                        // Username is taken, generate a suggestion
                        generateUniqueUsername(username, callback);
                    } else {
                        // Username is available
                        callback.onResult(true, null);
                    }
                } else {
                    Log.e(TAG, "Error checking username availability", task.getException());
                    // On error, allow registration to proceed
                    callback.onResult(true, null);
                }
            });
    }

    /**
     * Generate a unique username suggestion
     */
    private void generateUniqueUsername(String baseUsername, UsernameCheckCallback callback) {
        // Generate a random 4-digit number
        Random random = new Random();
        int randomNumber = 1000 + random.nextInt(9000); // 1000-9999
        String suggestedUsername = baseUsername + randomNumber;

        // Check if the suggested username is also taken
        checkUsernameAvailability(suggestedUsername, new UsernameCheckCallback() {
            @Override
            public void onResult(boolean isAvailable, String suggestedName) {
                if (isAvailable) {
                    callback.onResult(false, suggestedUsername);
                } else {
                    // If still taken, try another number
                    int newRandomNumber = 1000 + random.nextInt(9000);
                    String newSuggestion = baseUsername + newRandomNumber;
                    callback.onResult(false, newSuggestion);
                }
            }
        });
    }

    /**
     * Setup Google Sign-In configuration
     */
    private void setupGoogleSignIn() {
        try {
            // Get web client ID from resources
            String webClientId = getWebClientIdFromResources();

            if (webClientId == null) {
                Log.w(TAG, "Web client ID not properly configured");
                googleSignInButton.setEnabled(false);
                return;
            }

            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .requestProfile()
                    .build();

            googleSignInClient = GoogleSignIn.getClient(this, gso);

            // Check if Google Play Services are available
            if (!isGooglePlayServicesAvailable()) {
                googleSignInButton.setEnabled(false);
                googleSignInButton.setVisibility(View.GONE);
                Toast.makeText(this, "Google Play Services not available. Please use email/password sign-in.", Toast.LENGTH_SHORT).show();
            } else {
                googleSignInButton.setEnabled(true);
            }

        } catch (Exception e) {
            googleSignInButton.setEnabled(false);
            googleSignInButton.setVisibility(View.GONE);
            Toast.makeText(this, "Google Sign-In not available. Please use email/password sign-in.", Toast.LENGTH_SHORT).show();
        }
    }

    private void signInWithGoogle() {
        try {
            if (googleSignInClient == null) {
                Toast.makeText(this, "Google Sign-In not available. Please use email/password sign-in.", Toast.LENGTH_LONG).show();
                return;
            }

            if (!googleSignInButton.isEnabled()) {
                Toast.makeText(this, "Google Sign-In not available. Please use email/password sign-in.", Toast.LENGTH_LONG).show();
                return;
            }

            progressBar.setVisibility(View.VISIBLE);

            // Check Google Play Services availability again
            if (!isGooglePlayServicesAvailable()) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(this, "Google Play Services required for Google Sign-In", Toast.LENGTH_LONG).show();
                return;
            }

            // Sign out first to ensure fresh login
            googleSignInClient.signOut()
                    .addOnCompleteListener(this, task -> {

                        // Get sign-in intent
                        Intent signInIntent = googleSignInClient.getSignInIntent();

                        try {
                            googleSignInLauncher.launch(signInIntent);
                        } catch (Exception e) {
                            Toast.makeText(AuthActivity.this,
                                    "Error starting Google Sign-In. Please use email/password sign-in.",
                                    Toast.LENGTH_LONG).show();
                            progressBar.setVisibility(View.GONE);
                        }
                    })
                    .addOnFailureListener(e -> {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "Google Sign-In error. Please use email/password sign-in.", Toast.LENGTH_LONG).show();
                    });

        } catch (Exception e) {
            Log.e(TAG, "Error in signInWithGoogle", e);
            Toast.makeText(this, "Google Sign-In not available. Please use email/password sign-in.", Toast.LENGTH_LONG).show();
            progressBar.setVisibility(View.GONE);
        }
    }

    /**
     * Handle Google Sign-In result
     */
    private void handleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);
            Log.d(TAG, "Google Sign In successful, account email: " + account.getEmail());

            // Get ID token for Firebase auth
            String idToken = account.getIdToken();
            if (idToken != null) {
                Log.d(TAG, "Got ID token from Google Sign-In, authenticating with Firebase");
                firebaseAuthWithGoogle(idToken);
            } else {
                Log.e(TAG, "ID token from Google Sign-In is null");
                Toast.makeText(this, "Authentication error: Failed to get token. Please try email/password sign-in.", Toast.LENGTH_LONG).show();
                progressBar.setVisibility(View.GONE);
            }
        } catch (ApiException e) {
            // The ApiException status code indicates the detailed failure reason
            int statusCode = e.getStatusCode();
            String message = getGoogleSignInErrorMessage(statusCode);

            Log.e(TAG, "Google sign in failed: " + message + " (Status code: " + statusCode + ")", e);

            // Show user-friendly message encouraging email/password sign-in
            String userMessage = "Google Sign-In not available. Please use email and password to sign in.";
            if (statusCode == GoogleSignInStatusCodes.DEVELOPER_ERROR) {
                userMessage = "Google Sign-In configuration issue. Please use email/password sign-in instead.";
            }

            Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show();
            progressBar.setVisibility(View.GONE);
        }
    }

    /**
     * Get user-friendly error message for Google Sign-In status codes
     */
    private String getGoogleSignInErrorMessage(int statusCode) {
        switch (statusCode) {
            case GoogleSignInStatusCodes.SIGN_IN_CANCELLED:
                return "Sign-in was canceled";
            case GoogleSignInStatusCodes.NETWORK_ERROR:
                return "Network error occurred";
            case GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS:
                return "Sign-in already in progress";
            case GoogleSignInStatusCodes.SIGN_IN_FAILED:
                return "Sign-in failed";
            case GoogleSignInStatusCodes.INTERNAL_ERROR:
                return "Internal error";
            case GoogleSignInStatusCodes.DEVELOPER_ERROR:
                return "Configuration error - SHA-1 fingerprint or web client ID may be incorrect";
            default:
                return "Unknown error (code: " + statusCode + ")";
        }
    }

    /**
     * Handle Google Sign-In with unique username generation
     */
    private void firebaseAuthWithGoogle(String idToken) {
        try {
            if (idToken == null) {
                Log.e(TAG, "Google Sign-In failed: ID token is null");
                Toast.makeText(AuthActivity.this, "Authentication error: Token is missing",
                        Toast.LENGTH_SHORT).show();
                progressBar.setVisibility(View.GONE);
                return;
            }

            Log.d(TAG, "Authenticating with Firebase using Google ID token");
            AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
            mAuth.signInWithCredential(credential)
                    .addOnCompleteListener(this, task -> {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "signInWithCredential:success");
                            FirebaseUser user = mAuth.getCurrentUser();
                            if (user != null) {
                                String displayName = user.getDisplayName() != null ? user.getDisplayName() : "User";
                                String email = user.getEmail() != null ? user.getEmail() : "";
                                Log.d(TAG, "Firebase authentication successful with Google: " + displayName);

                                // Check if user already exists in database
                                checkExistingUserAndCreateProfile(user, displayName, email);
                            } else {
                                progressBar.setVisibility(View.GONE);
                                Log.w(TAG, "User is null after successful authentication");
                                Toast.makeText(AuthActivity.this, "Error: User not found after sign-in",
                                        Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            progressBar.setVisibility(View.GONE);
                            Exception exception = task.getException();
                            Log.w(TAG, "signInWithCredential:failure", exception);

                            // Check for specific Google Play Services error
                            String errorMsg;
                            if (exception != null && exception.getMessage() != null &&
                                exception.getMessage().contains("API_NOT_CONNECTED")) {
                                errorMsg = "Google Play Services error - Please check your Google Play Services";
                                // Try to recover by initializing Google Sign-In again
                                setupGoogleSignIn();
                            } else if (exception != null && exception.getMessage() != null &&
                                      exception.getMessage().contains("DEVELOPER_ERROR")) {
                                errorMsg = "Authentication configuration error - Please contact support";
                            } else {
                                errorMsg = exception != null ? exception.getMessage() : "Unknown error";
                            }

                            Toast.makeText(AuthActivity.this,
                                    "Authentication failed: " + errorMsg,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error in firebaseAuthWithGoogle", e);
            Toast.makeText(this, "Authentication error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            progressBar.setVisibility(View.GONE);
        }
    }

    /**
     * Check if user already exists and create/update profile accordingly
     */
    private void checkExistingUserAndCreateProfile(FirebaseUser user, String displayName, String email) {
        if (db == null) {
            Log.e(TAG, "Firestore database is null - proceeding without profile check");
            updateUserProfile(user, cleanUsernameFromDisplayName(displayName), email);
            return;
        }

        db.collection("users")
            .document(user.getUid())
            .get()
            .addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    DocumentSnapshot document = task.getResult();

                    if (document != null && document.exists()) {
                        // User already exists, just proceed to main activity
                        Log.d(TAG, "Existing user signed in with Google");
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(AuthActivity.this, "Welcome back, " + displayName + "!", Toast.LENGTH_SHORT).show();
                        proceedToMainActivity(user);
                    } else {
                        // New user, create profile with unique username
                        createUniqueUsernameForGoogleUser(user, displayName, email);
                    }
                } else {
                    Log.e(TAG, "Error checking existing user", task.getException());
                    // Proceed anyway with default profile creation
                    updateUserProfile(user, cleanUsernameFromDisplayName(displayName), email);
                }
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Firestore failure when checking user", e);
                // Proceed anyway with default profile creation
                updateUserProfile(user, cleanUsernameFromDisplayName(displayName), email);
            });
    }

    /**
     * Create unique username for Google Sign-In user
     */
    private void createUniqueUsernameForGoogleUser(FirebaseUser user, String displayName, String email) {
        // Clean the display name to create a username
        String baseUsername = cleanUsernameFromDisplayName(displayName);

        // Check if this username is available
        checkUsernameAvailability(baseUsername, (isAvailable, suggestedName) -> {
            String finalUsername;

            if (isAvailable) {
                finalUsername = baseUsername;
            } else {
                // Generate unique username with 4-digit number
                Random random = new Random();
                int randomNumber = 1000 + random.nextInt(9000);
                finalUsername = baseUsername + randomNumber;
            }

            // Create user profile with unique username
            updateUserProfile(user, finalUsername, email);
        });
    }

    /**
     * Clean display name to create a valid username
     */
    private String cleanUsernameFromDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return "user";
        }

        // Remove spaces and special characters, keep only letters, numbers, dots, underscores
        String cleaned = displayName.toLowerCase()
                .replaceAll("[^a-z0-9._]", "")
                .replaceAll("\\s+", "");

        // Ensure it's not empty and has minimum length
        if (cleaned.length() < 3) {
            cleaned = "user" + cleaned;
        }

        // Ensure it's not too long
        if (cleaned.length() > 20) {
            cleaned = cleaned.substring(0, 20);
        }

        return cleaned;
    }

    /**
     * Create user with email and password
     */
    private void createUserWithEmailAndPassword(String email, String password, String name) {
        if (mAuth == null) {
            Log.e(TAG, "Firebase Auth is null, cannot create user");
            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, "Authentication service unavailable", Toast.LENGTH_SHORT).show();
            return;
        }

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "createUserWithEmail:success");
                        FirebaseUser user = mAuth.getCurrentUser();

                        if (user != null) {
                            // Send email verification
                            user.sendEmailVerification()
                                    .addOnCompleteListener(emailTask -> {
                                        if (emailTask.isSuccessful()) {
                                            Log.d(TAG, "Verification email sent to " + user.getEmail());

                                            // Update user profile and create user document
                                            updateUserProfile(user, name, email);

                                            // Show verification dialog
                                            progressBar.setVisibility(View.GONE);
                                            showEmailVerificationSentDialog(email);
                                        } else {
                                            Log.w(TAG, "Failed to send verification email", emailTask.getException());
                                            progressBar.setVisibility(View.GONE);
                                            Toast.makeText(AuthActivity.this,
                                                    "Account created but failed to send verification email. " +
                                                    "Please try signing in and requesting verification again.",
                                                    Toast.LENGTH_LONG).show();

                                            // Still update profile even if email verification fails
                                            updateUserProfile(user, name, email);
                                        }
                                    });
                        } else {
                            progressBar.setVisibility(View.GONE);
                            Log.w(TAG, "User is null after successful registration");
                            Toast.makeText(AuthActivity.this, "Registration completed but user not found", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        progressBar.setVisibility(View.GONE);
                        Exception exception = task.getException();
                        Log.w(TAG, "createUserWithEmail:failure", exception);

                        String errorMessage = "Registration failed";
                        if (exception != null && exception.getMessage() != null) {
                            String message = exception.getMessage();
                            if (message.contains("email address is already in use")) {
                                errorMessage = "This email is already registered. Please use a different email or try signing in.";
                            } else if (message.contains("weak password")) {
                                errorMessage = "Password is too weak. Please use a stronger password.";
                            } else if (message.contains("invalid email")) {
                                errorMessage = "Invalid email address format.";
                            } else {
                                errorMessage = "Registration failed: " + message;
                            }
                        }

                        Toast.makeText(AuthActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void updateUserProfile(FirebaseUser user, String username, String email) {
        // Create user document in Firestore with username validation
        Map<String, Object> userData = new HashMap<>();
        userData.put("name", username);
        userData.put("username", username);
        userData.put("username_normalized", username.toLowerCase()); // For case-insensitive searches
        userData.put("email", email);
        userData.put("created_at", System.currentTimeMillis());
        userData.put("device_model", android.os.Build.MODEL);

        // For Google accounts, automatically mark email as verified
        boolean isGoogleAccount = user.getProviderData().stream()
            .anyMatch(info -> "google.com".equals(info.getProviderId()));
        userData.put("email_verified", isGoogleAccount);

        db.collection("users")
                .document(user.getUid())
                .set(userData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "User document created with ID: " + user.getUid() + " and username: " + username);

                    // Store user info in shared preferences
                    SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
                    prefs.edit()
                            .putString("user_name", username)
                            .putString("user_email", email)
                            .putString("user_id", user.getUid())
                            .putBoolean("first_launch", false)
                            .putLong("user_created_timestamp", System.currentTimeMillis())
                            .apply();

                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(AuthActivity.this, "Welcome, " + username + "!", Toast.LENGTH_SHORT).show();
                    proceedToMainActivity(user);
                })
                .addOnFailureListener(e -> {
                    progressBar.setVisibility(View.GONE);
                    Log.w(TAG, "Error creating user document", e);
                    // Still proceed to main activity even if Firestore update fails
                    Toast.makeText(AuthActivity.this, "Welcome, " + username + "!", Toast.LENGTH_SHORT).show();
                    proceedToMainActivity(user);
                });
    }

    private void proceedToMainActivity(FirebaseUser user) {
        try {
            // Store user data in SharedPreferences for both Firebase and fallback modes
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);

            if (user != null) {
                // Firebase user
                String displayName = user.getDisplayName() != null ? user.getDisplayName() : "User";
                String email = user.getEmail() != null ? user.getEmail() : "";

                prefs.edit()
                        .putString("user_name", displayName)
                        .putString("user_email", email)
                        .putString("user_id", user.getUid())
                        .putBoolean("first_launch", false)
                        .putBoolean("auto_backup_enabled", true) // Auto-backup ON by default for Firebase users
                        .putBoolean("firebase_unavailable", false)
                        .apply();
            } else {
                // Fallback mode - data already saved in login/register methods
                prefs.edit()
                        .putBoolean("first_launch", false)
                        .putBoolean("auto_backup_enabled", false) // Disable auto-backup in fallback mode
                        .apply();
            }

            // Start MainActivity with clear flags
            Intent intent = new Intent(AuthActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Error proceeding to MainActivity", e);

            // Show error toast and try again
            Toast.makeText(this, "Error starting main app. Please try again.", Toast.LENGTH_SHORT).show();
        }
    }


    /**
     * Get web client ID from resources with better validation
     */
    private String getWebClientIdFromResources() {
        try {
            String webClientId = getString(R.string.default_web_client_id);

            // Check if it's a placeholder value
            if (webClientId.equals("YOUR_WEB_CLIENT_ID_HERE")) {
                Log.w(TAG, "Placeholder web client ID found - Google Sign-In disabled");
                return null;
            }

            // Check if it's a properly formatted client ID
            if (!webClientId.contains("-") || !webClientId.contains(".apps.googleusercontent.com")) {
                Log.w(TAG, "Invalid web client ID format - Google Sign-In disabled");
                return null;
            }

            return webClientId;
        } catch (Exception e) {
            Log.e(TAG, "Error getting web client ID from resources", e);
            return null;
        }
    }

    /**
     * Check if Google Play Services are available
     */
    private boolean isGooglePlayServicesAvailable() {
        try {
            com.google.android.gms.common.GoogleApiAvailability apiAvailability =
                com.google.android.gms.common.GoogleApiAvailability.getInstance();
            int resultCode = apiAvailability.isGooglePlayServicesAvailable(this);

            if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                Log.e(TAG, "Google Play Services not available: " + resultCode);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error checking Google Play Services", e);
            return false;
        }
    }

    /**
     * Callback interface for username availability check
     */
    private interface UsernameCheckCallback {
        void onResult(boolean isAvailable, String suggestedName);
    }

    /**
     * Show forgot password dialog
     */
    private void showForgotPasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        // Create custom view
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(32, 24, 32, 24);
        dialogLayout.setBackgroundColor(getResources().getColor(R.color.bw_surface, null));

        // Title
        TextView title = new TextView(this);
        title.setText("Reset Password");
        title.setTextSize(20);
        title.setTextColor(getResources().getColor(R.color.bw_primary_text, null));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, 16);
        dialogLayout.addView(title);

        // Description
        TextView description = new TextView(this);
        description.setText("Enter your email address and we'll send you a link to reset your password.");
        description.setTextSize(14);
        description.setTextColor(getResources().getColor(R.color.bw_secondary_text, null));
        description.setGravity(android.view.Gravity.CENTER);
        description.setPadding(0, 0, 0, 24);
        description.setLineSpacing(4, 1.2f);
        dialogLayout.addView(description);

        // Email input
        com.google.android.material.textfield.TextInputLayout emailLayout =
                new com.google.android.material.textfield.TextInputLayout(this);
        emailLayout.setHint("Email Address");
        emailLayout.setStartIconDrawable(R.drawable.ic_email_24);
        emailLayout.setStartIconTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.bw_accent, null)));
        emailLayout.setBoxStrokeColorStateList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.bw_accent, null)));
        emailLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.bw_accent, null)));
        emailLayout.setBoxBackgroundColor(getResources().getColor(R.color.bw_input_background, null));

        com.google.android.material.textfield.TextInputEditText resetEmailInput =
                new com.google.android.material.textfield.TextInputEditText(this);
        resetEmailInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        resetEmailInput.setMaxLines(1);
        resetEmailInput.setTextColor(getResources().getColor(R.color.bw_primary_text, null));

        // Pre-fill with current email if available
        String currentEmail = emailEditText.getText().toString().trim();
        if (!currentEmail.isEmpty()) {
            resetEmailInput.setText(currentEmail);
        }

        emailLayout.addView(resetEmailInput);
        dialogLayout.addView(emailLayout);

        builder.setView(dialogLayout);
        builder.setPositiveButton("Send Reset Link", null);
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();

        // Set custom positive button action
        dialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

            // Style buttons to match black and white theme
            if (positiveButton != null) {
                positiveButton.setBackgroundColor(getResources().getColor(R.color.bw_primary_button, null));
                positiveButton.setTextColor(getResources().getColor(R.color.bw_button_text, null));
                positiveButton.setPadding(24, 12, 24, 12);
            }

            if (negativeButton != null) {
                negativeButton.setBackgroundColor(getResources().getColor(R.color.bw_secondary_button, null));
                negativeButton.setTextColor(getResources().getColor(R.color.bw_primary_text, null));
                negativeButton.setPadding(24, 12, 24, 12);
            }

            positiveButton.setOnClickListener(v -> {
                String resetEmail = resetEmailInput.getText().toString().trim();

                if (android.text.TextUtils.isEmpty(resetEmail)) {
                    resetEmailInput.setError("Email is required");
                    return;
                }

                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(resetEmail).matches()) {
                    resetEmailInput.setError("Please enter a valid email address");
                    return;
                }

                // Send password reset email
                sendPasswordResetEmail(resetEmail, dialog);
            });
        });

        dialog.show();
    }

    /**
     * Send password reset email
     */
    private void sendPasswordResetEmail(String email, AlertDialog dialog) {
        if (mAuth == null) {
            Toast.makeText(this, "Password reset is not available in offline mode.", Toast.LENGTH_LONG).show();
            dialog.dismiss();
            return;
        }

        Log.d(TAG, "Sending password reset email to: " + email);

        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    dialog.dismiss();

                    if (task.isSuccessful()) {
                        Log.d(TAG, "Password reset email sent successfully");
                        Toast.makeText(this, "Reset link sent to " + email + ". Check your email.", Toast.LENGTH_LONG).show();
                    } else {
                        Log.e(TAG, "Failed to send password reset email", task.getException());
                        String errorMessage = "Failed to send reset email.";

                        if (task.getException() != null) {
                            String exceptionMessage = task.getException().getMessage();
                            if (exceptionMessage != null) {
                                if (exceptionMessage.contains("no user record")) {
                                    errorMessage = "No account found with this email address.";
                                } else if (exceptionMessage.contains("invalid email")) {
                                    errorMessage = "Invalid email address format.";
                                } else if (exceptionMessage.contains("too many requests")) {
                                    errorMessage = "Too many reset attempts. Please try again later.";
                                }
                            }
                        }

                        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
                    }
                })
                .addOnFailureListener(e -> {
                    dialog.dismiss();
                    Log.e(TAG, "Exception sending password reset email", e);
                    Toast.makeText(this, "An error occurred. Please try again.", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * Setup password visibility toggle functionality with proper icons
     */
    private void setupPasswordToggle() {
        try {
            // Get the password input layout
            com.google.android.material.textfield.TextInputLayout passwordLayout =
                findViewById(R.id.passwordInputLayout);

            if (passwordLayout != null) {
                // Track password visibility state
                final boolean[] isPasswordVisible = {false};

                // Set initial state - password hidden, show "eye-off" icon (hide password icon)
                passwordLayout.setEndIconDrawable(R.drawable.ic_visibility_off);

                // Set up end icon click listener
                passwordLayout.setEndIconOnClickListener(view -> {
                    if (isPasswordVisible[0]) {
                        // Password is currently visible - hide it
                        passwordEditText.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        passwordLayout.setEndIconDrawable(R.drawable.ic_visibility_off);
                        isPasswordVisible[0] = false;
                        Log.d(TAG, "Password hidden - showing eye-off icon");
                    } else {
                        // Password is currently hidden - show it
                        passwordEditText.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                        passwordLayout.setEndIconDrawable(R.drawable.ic_visibility);
                        isPasswordVisible[0] = true;
                        Log.d(TAG, "Password visible - showing eye icon");
                    }

                    // Move cursor to end of text
                    passwordEditText.setSelection(passwordEditText.getText().length());
                });

                Log.d(TAG, "Password toggle functionality setup complete");
            } else {
                Log.w(TAG, "Password input layout not found");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up password toggle functionality", e);
        }
    }
}
