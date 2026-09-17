package com.pennywiseai.tracker.data.email

/**
 * A credential store with no keystore behind it. Test-only.
 *
 * It keeps the shape of the real one exactly — no getter, only [withPassword] — so a test
 * that compiles against this fake is proof that the production code never asked for the
 * password in a way the encrypted store would refuse to answer.
 *
 * [borrowCount] is how a test asserts that a run read the credential once, not once per
 * message.
 */
class FakeEmailCredentialStore(
    password: AppPassword? = null
) : EmailCredentialStore {

    private var stored: AppPassword? = password

    var borrowCount: Int = 0
        private set

    var storeCount: Int = 0
        private set

    var clearCount: Int = 0
        private set

    override suspend fun hasPassword(): Boolean = stored != null

    override suspend fun store(password: AppPassword) {
        storeCount++
        stored = password
    }

    override suspend fun <T> withPassword(block: suspend (AppPassword) -> T): T? {
        val password = stored ?: return null
        borrowCount++
        return block(password)
    }

    override suspend fun clear() {
        clearCount++
        stored = null
    }

    companion object {
        /**
         * A store that already holds a valid password.
         *
         * The sample is sixteen letters: a digit-based one would be a sixteen-digit run,
         * which is the shape `PiiLintTest` fails the build on.
         */
        fun configured(): FakeEmailCredentialStore =
            FakeEmailCredentialStore(samplePassword())

        fun samplePassword(): AppPassword = requireNotNull(AppPassword.parse("abcdefghijklmnop")) {
            "AppPassword.parse rejected the 16-character sample"
        }
    }
}
