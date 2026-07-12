"""Async client for the Sunmi Printer app's HTTP print server."""
from __future__ import annotations

from typing import Any

import aiohttp
from homeassistant.exceptions import HomeAssistantError

HEALTH_CHECK_TIMEOUT = aiohttp.ClientTimeout(total=5)
PRINT_TIMEOUT = aiohttp.ClientTimeout(total=10)


class SunmiPrinterError(HomeAssistantError):
    """Base error for the Sunmi Printer integration."""


class SunmiPrinterConnectionError(SunmiPrinterError):
    """Raised when the device cannot be reached."""


class SunmiPrinterResponseError(SunmiPrinterError):
    """Raised when the device responds with an unexpected status."""


class SunmiPrinterClient:
    """Thin wrapper around the app's `GET /` and `POST /print` endpoints.

    A 200 from `/print` only means the job was enqueued on the device's
    single-thread print executor, not that it actually printed — the app has
    no completion callback, so paper-out/jam/etc. failures are never
    reported back over HTTP.
    """

    def __init__(self, session: aiohttp.ClientSession, host: str, port: int) -> None:
        self._session = session
        self._base_url = f"http://{host}:{port}"

    async def async_health_check(self) -> None:
        """GET / — side-effect-free, used by the config flow to validate a device."""
        try:
            async with self._session.get(
                self._base_url + "/", timeout=HEALTH_CHECK_TIMEOUT
            ) as resp:
                if resp.status != 200:
                    raise SunmiPrinterResponseError(f"Unexpected status {resp.status}")
        except (aiohttp.ClientError, TimeoutError) as err:
            raise SunmiPrinterConnectionError(str(err)) from err

    async def async_print(self, job: dict[str, Any]) -> None:
        """POST /print with a PrintJob-shaped payload (camelCase keys)."""
        payload = {k: v for k, v in job.items() if v is not None}
        try:
            async with self._session.post(
                self._base_url + "/print", json=payload, timeout=PRINT_TIMEOUT
            ) as resp:
                if resp.status != 200:
                    body = await resp.text()
                    raise SunmiPrinterResponseError(f"HTTP {resp.status}: {body}")
        except (aiohttp.ClientError, TimeoutError) as err:
            raise SunmiPrinterConnectionError(str(err)) from err
