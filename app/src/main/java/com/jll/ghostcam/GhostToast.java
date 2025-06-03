package com.jll.ghostcam;



import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.StringRes; // It's good practice to add this for resource IDs


@SuppressLint("MissingInflatedId")
public class GhostToast {

    // No need for a static Toast field here if we create a new one each time, which is safer.

    /**
     * Creates and configures a custom Toast.
     *
     * @param context  The context to use.
     * @param message  The text to show in the Toast.
     * @param duration How long to display the message. Either {@link Toast#LENGTH_SHORT} or {@link Toast#LENGTH_LONG}.
     * @return A new, configured Toast object.
     */
    private static Toast createCustomToast(Context context, String message, int duration) {
        // Inflate the custom layout
        LayoutInflater inflater = LayoutInflater.from(context);
        // The root ViewGroup is null because this view is for a Toast, which manages its own window.
        View layout = inflater.inflate(R.layout.custom_toast, null);

        // Set the text
        TextView text = layout.findViewById(R.id.toast_text);
        text.setText(message);

        // Set the logo
        ImageView logo = layout.findViewById(R.id.toast_logo);
        logo.setImageResource(R.mipmap.ic_launcher); // Ensure ic_logo exists in res/drawable

        // Create a new Toast instance
        Toast customToast = new Toast(context);
        customToast.setDuration(duration);
        customToast.setView(layout);

        // Set gravity: 80 (decimal) is Gravity.BOTTOM.
        // The xOffset is 0, yOffset is 100.
        customToast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 100);
        // If it was strictly Gravity.BOTTOM (0x50), then just Gravity.BOTTOM
        // Gravity.BOTTOM is 80 (0x50). Gravity.CENTER_HORIZONTAL is 1 (0x01).
        // So 80 is indeed Gravity.BOTTOM. If it was BOTTOM | CENTER_HORIZONTAL, it would be 81.
        // The original code's '80' means just Gravity.BOTTOM.
        // Let's stick to the original decompiled gravity for directness, but if `Gravity.BOTTOM` is sufficient:
        // customToast.setGravity(Gravity.BOTTOM, 0, 100);

        return customToast;
    }

    /**
     * Make a custom toast that justcontains a text view.
     *
     * @param context      The context to use. Usually your {@link android.app.Application}
     *                     or {@link android.app.Activity} object.
     * @param messageResId The resource id of the string resource to use. Can be formatted text.
     * @param duration     How long to display the message. Either {@link Toast#LENGTH_SHORT} or
     *                     {@link Toast#LENGTH_LONG}
     */
    public static Toast makeText(Context context, @StringRes int messageResId, int duration) {
        String message = context.getResources().getString(messageResId);
        return createCustomToast(context, message, duration);
    }

    /**
     * Make a custom toast that just contains a text view.
     *
     * @param context  The context to use. Usually your {@link android.app.Application}
     *                 or {@link android.app.Activity} object.
     * @param message  The text to show. Can be formatted text.
     * @param duration How long to display the message. Either {@link Toast#LENGTH_SHORT} or
     *                 {@link Toast#LENGTH_LONG}
     */
    public static Toast makeText(Context context, CharSequence message, int duration) {
        return createCustomToast(context, message.toString(), duration);
    }
}

