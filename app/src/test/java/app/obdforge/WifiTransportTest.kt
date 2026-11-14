package app.obdforge

import app.obdforge.connection.WifiTransport
import app.obdforge.core.elm.ObdProtocol
import app.obdforge.core.obd.ObdSession
import app.obdforge.core.sim.SimulatedElm327
import app.obdforge.core.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Exercises the real Wi-Fi transport over TCP, the way a Wi-Fi ELM327 is reached, with the
 * simulator behind a local socket: connect, protocol detection, data, and link loss.
 */
class WifiTransportTest {
    private fun startTcpAdapter(): Pair<ServerSocket, () -> Socket?> {
        val sim = SimulatedElm327(latencyMs = 0)
        sim.open()
        val server = ServerSocket(0)
        var client: Socket? = null
        thread(isDaemon = true) {
            val s = server.accept()
            client = s
            thread(isDaemon = true) {
                val buf = ByteArray(256)
                runCatching {
                    while (true) {
                        val n = s.getInputStream().read(buf)
                        if (n < 0) break
                        sim.write(buf.copyOf(n))
                    }
                }
            }
            runCatching {
                val out = s.getOutputStream()
                while (!s.isClosed) {
                    val b = sim.read(100)
                    if (b.isNotEmpty()) { out.write(b); out.flush() }
                }
            }
        }
        return server to { client }
    }

    @Test fun connectsAndReadsOverTcp() {
        val (server, _) = startTcpAdapter()
        val session = ObdSession(WifiTransport("127.0.0.1", server.localPort))
        val r = session.connect()
        assertEquals(ObdProtocol.CAN_11_500, r.protocol)
        assertEquals("SIMULATED0VIN0001", r.vin)
        assertTrue(session.client.readPids(listOf(0x0C, 0x0D)).containsKey(0x0C))
        session.close()
        server.close()
    }

    @Test fun linkLossIsReported() {
        val (server, client) = startTcpAdapter()
        val session = ObdSession(WifiTransport("127.0.0.1", server.localPort))
        session.connect()
        client()!!.close()
        Thread.sleep(300)
        try {
            session.client.readPids(listOf(0x0C))
            fail("expected the lost link to surface")
        } catch (e: TransportException) {
            // expected
        }
        session.close()
        server.close()
    }

    @Test fun unreachableAdapterGivesClearError() {
        val port = ServerSocket(0).use { it.localPort } // closed again: nothing listens there
        try {
            WifiTransport("127.0.0.1", port).open()
            fail("should not connect")
        } catch (e: TransportException) {
            assertTrue(e.message!!.contains("Could not reach"))
        }
    }
}
