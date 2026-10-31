package pro.hattie.app

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The house's certificate is made by the house.
 *
 * WHY THIS EXISTS AT ALL. Chromium will not hand a microphone to a page on
 * plain http — that is the whole reason the page said "no microphone access
 * in this browser" on a tablet, and no permission dialog ever appeared,
 * because the request is refused before one is asked for. The fix is for
 * her page to be https. But the box is a box in a cupboard: it has no
 * domain, no public certificate and nobody to renew one, so it signs its
 * own, and every normal TLS client — including this app's own HTTP calls —
 * refuses a certificate it did not come from a known authority.
 *
 * SO THE APP TRUSTS THE HOUSE, AND ONLY THE HOUSE. Everything this app talks
 * to is the one address somebody typed on the setup screen; there is no
 * third party in the code at all, no analytics, no update check. In that
 * world a public certificate authority adds nothing: there is no second
 * party to be impersonated by.
 *
 * THE HONEST LIMIT: this accepts any certificate the paired address offers,
 * which would also accept a man in the middle on the family's own network.
 * The tighter version — remember this certificate's fingerprint on the first
 * successful pairing and refuse anything else afterwards — is the right next
 * step, and is written down in Disyard/Scratch-Install.md rather than
 * pretended to be done here.
 */
object Trust {

    fun allowTheHouse() {
        runCatching {
            val permissive = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(arrayOf<KeyManager>(), arrayOf<TrustManager>(permissive), SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
            /* The certificate is for an address like 192.168.1.10, which no
             * hostname check can be happy with anyway; the paired base is the
             * only thing we ever connect to. */
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        }
    }
}
