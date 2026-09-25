package com.learntoplay.app

/**
 * On/off switches for the features the stripped-down MVP build hides (branch `mvp/simplified`).
 *
 * The MVP ships only the core loop: PIN-protected Admin Mode, manually entered questions, the
 * quiz + time bank, Gated Apps, and Allowed Hours. Everything below is still fully present in
 * the codebase — these flags only hide its entry points (buttons, screens, the share-sheet
 * target) and switch off its background work, so any of it can be brought back by flipping a
 * single value here rather than restoring deleted code.
 *
 * NOTE: [AI_QUESTION_TOOLS] also has a twin in res/values/feature_flags.xml
 * (`share_to_app_enabled`), because whether the app shows up in other apps' share sheets is
 * decided by the manifest, which can't read a Kotlin constant. Keep the two in sync.
 */
object FeatureFlags {

    /** "Scan Photo" (camera + OCR), "From Topic" (on-device AI batch generation), and
     * share-to-app (turning a shared WhatsApp message/image into questions). Off: parents add
     * questions by hand with the "+" button on the Questions screen. */
    const val AI_QUESTION_TOOLS = false

    /** The bundled sample curricula (CBSE Grade 5 Science/Maths) and their questions. Off: the
     * curriculum list shows only curricula the parent created themselves (plus any preset that
     * is already the active one on an existing install, so it never silently disappears). */
    const val CURRICULUM_PRESETS = false

    /** Parent Account (email sign-in), Remote Access pairing code, "View a paired child
     * device", and all Firestore sync/remote commands behind them. Off: nothing about the
     * child's usage leaves the device — there's no visible feature it would be serving. */
    const val REMOTE_AND_PARENT_ACCOUNT = false

    /** Device Owner "Tamper Protection" (blocks uninstall / Safe Mode / factory reset). Off:
     * the card is hidden. The Accessibility/overlay "protection turned off" alert
     * (ProtectionMonitor) is core enforcement, not part of this flag, and stays on. */
    const val TAMPER_PROTECTION = false
}
