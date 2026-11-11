"""环境变量工具。

STS_TEST_DEVICE      — 默认设备序列号（默认 "auto"）
STS_CONNECTOR_PORT   — connector daemon 端口（ConnectorClient 读取）
"""
import os

def get_test_device_serial() -> str:
    return os.environ.get("STS_TEST_DEVICE", "auto")
