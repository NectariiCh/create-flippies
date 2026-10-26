import unittest

import paths
import sysprobe

CPUINFO = """CPU:\t\t68040
MMU:\t\t68040
FPU:\t\t68040
Clocking:\t24.9MHz
BogoMips:\t16.53
Calibration:\t82688 loops
"""

MEMINFO = """MemTotal:         126976 kB
MemFree:           10000 kB
MemAvailable:      80000 kB
Buffers:            2000 kB
Cached:            30000 kB
SwapTotal:        131068 kB
SwapFree:         131068 kB
"""

NETDEV = """Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:     100       2    0    0    0     0          0         0      100       2    0    0    0     0       0          0
  eth0: 1234567    1000    0    0    0     0          0         0    76543     500    0    0    0     0       0          0
"""

ROUTE_LE = """Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT
eth0\t00000000\t0100580A\t0003\t0\t0\t0\t00000000\t0\t0\t0
eth0\t0058580A\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0
"""


class ProbeTest(unittest.TestCase):
    def test_cpu(self):
        self.assertEqual(sysprobe.cpu(CPUINFO), [("CPU", "68040"), ("FPU", "68040"), ("MMU", "68040"),
                                                 ("Clock", "24.9MHz"), ("BogoMips", "16.53")])
        self.assertEqual(sysprobe.cpu("model name\t: Intel\nmodel name\t: Intel\n"), [("CPU", "Intel")])
        self.assertEqual(sysprobe.cpu(None), [])

    def test_memory(self):
        self.assertEqual(sysprobe.memory(MEMINFO), (126976 * 1024, 80000 * 1024, 131068 * 1024, 131068 * 1024))
        old = MEMINFO.replace("MemAvailable:      80000 kB\n", "")
        self.assertEqual(sysprobe.memory(old)[1], 42000 * 1024)
        self.assertEqual(sysprobe.memory(None), (None, None, None, None))

    def test_network(self):
        self.assertEqual(sysprobe.net_bytes(NETDEV), (1234567, 76543))
        self.assertEqual(sysprobe.net_bytes(NETDEV, "eth1"), (None, None))
        self.assertEqual(sysprobe.gateway(ROUTE_LE, byteorder="little"), "10.88.0.1")
        big = ROUTE_LE.replace("0100580A", "0A580001")
        self.assertEqual(sysprobe.gateway(big, byteorder="big"), "10.88.0.1")
        self.assertIsNone(sysprobe.gateway("Iface\n"))

    def test_misc(self):
        self.assertEqual(sysprobe.uptime("3725.12 100.00\n"), 3725.12)
        self.assertIsNone(sysprobe.uptime(None))
        self.assertEqual(sysprobe.load("0.50 0.40 0.30 1/50 123\n"), "0.50 0.40 0.30")
        self.assertEqual(sysprobe.duration(3725), "1 h 2 min")
        self.assertEqual(sysprobe.duration(90061), "1 d 1 h")
        self.assertEqual(sysprobe.duration(72), "1 min 12 s")
        self.assertEqual(sysprobe.size(512), "512 B")
        self.assertEqual(sysprobe.size(126976 * 1024), "124.0 MiB")

    def test_snapshot_runs_here(self):
        s = sysprobe.snapshot()
        self.assertIn("host", s)
        total, free = s["disk"]
        self.assertGreater(total, 0)


if __name__ == "__main__":
    unittest.main()
