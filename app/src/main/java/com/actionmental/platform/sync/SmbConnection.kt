package com.actionmental.platform.sync

import com.actionmental.core.sync.SyncConfig
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 到 NAS 共享的一条已认证连接。
 *
 * 几个刻意的选择：
 * - 认证只开 NTLM：SMB 库默认还会装 Kerberos / SPNEGO，它们依赖 Android 上不存在的 `org.ietf.jgss`；
 *   家用与小型办公 NAS（QNAP 的 Samba）用的都是 NTLM。
 * - 加密原语显式用 BouncyCastle 的轻量实现：不经 JCA 的 provider 名字查找，
 *   也就不受 Android 自带的阉割版 BC 影响，R8 也不会把它当成无引用的类删掉。
 * - 先用一个短超时的 TCP 探测：主机不在线时库自己的连接超时很长，探测几秒就能给出明确的原因。
 */
class SmbConnection private constructor(
    private val client: SMBClient,
    private val session: Session,
    val share: DiskShare,
) : Closeable {

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { client.close() }
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 6_000

        fun open(config: SyncConfig, password: CharArray): SmbConnection {
            probe(config.host, config.port)

            val smb = SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withAuthenticators(NtlmAuthenticator.Factory())
                .withMultiProtocolNegotiate(true)
                .withDfsEnabled(false)
                .build()
            val client = SMBClient(smb)
            try {
                val connection = client.connect(config.host, config.port)
                val session = connection.authenticate(authContext(config.username, password))
                val share = session.connectShare(config.share) as? DiskShare
                    ?: throw IOException("「" + config.share + "」不是文件共享")
                return SmbConnection(client, session, share)
            } catch (e: Throwable) {
                runCatching { client.close() }
                throw e
            }
        }

        private fun authContext(username: String, password: CharArray): AuthenticationContext {
            if (username.isBlank()) return AuthenticationContext.guest()
            // 域用户写成 DOMAIN\user；NAS 本地账户不带域
            val domain = username.substringBefore('\\', "")
            val user = username.substringAfter('\\')
            return AuthenticationContext(user, password, domain)
        }

        private fun probe(host: String, port: Int) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
            } catch (e: UnknownHostException) {
                throw IOException("找不到主机 " + host + " · 检查地址，或改用 IP", e)
            } catch (e: SocketTimeoutException) {
                throw IOException("连接 " + host + ":" + port + " 超时 · NAS 不在线，或不在同一网络", e)
            } catch (e: IOException) {
                throw IOException("连不上 " + host + ":" + port + " · " + (e.message ?: "端口未开放"), e)
            }
        }

        /** 把库抛出的各种异常翻成一句能照着去改的话。 */
        fun describe(e: Throwable): String {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is SMBApiException) {
                    return when (cause.status) {
                        NtStatus.STATUS_LOGON_FAILURE -> "用户名或密码不对"
                        NtStatus.STATUS_ACCOUNT_DISABLED -> "这个账户在 NAS 上被停用了"
                        NtStatus.STATUS_BAD_NETWORK_NAME -> "NAS 上没有这个共享名"
                        NtStatus.STATUS_ACCESS_DENIED -> "没有权限 · 检查这个账户对该共享的读写权限"
                        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "NAS 上找不到这个路径"
                        NtStatus.STATUS_SHARING_VIOLATION -> "文件正被其他程序占用"
                        NtStatus.STATUS_DISK_FULL -> "NAS 空间不足"
                        else -> cause.status.name.removePrefix("STATUS_").lowercase().replace('_', ' ')
                    }
                }
                cause = cause.cause?.takeIf { it !== cause }
            }
            return e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }
}
