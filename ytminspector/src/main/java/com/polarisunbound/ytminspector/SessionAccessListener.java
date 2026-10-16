package com.polarisunbound.ytminspector;

import android.service.notification.NotificationListenerService;

/**
 * Grants MediaSessionManager access when the user explicitly enables
 * notification access in Android settings. This diagnostic app doesn't read,
 * store, transmit or display notification content.
 */
public final class SessionAccessListener extends NotificationListenerService {
}
