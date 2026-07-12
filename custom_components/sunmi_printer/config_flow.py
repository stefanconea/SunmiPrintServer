"""Config flow for the Sunmi Printer integration."""
from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_HOST, CONF_NAME, CONF_PORT
from homeassistant.core import callback
from homeassistant.helpers import selector
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import SunmiPrinterClient, SunmiPrinterConnectionError, SunmiPrinterError
from .const import (
    CONF_DEFAULT_CONTENT_SIZE,
    CONF_DEFAULT_LINES_AFTER,
    CONF_DEFAULT_TITLE_SIZE,
    CONF_DEFAULT_TYPE,
    DEFAULT_CONTENT_SIZE,
    DEFAULT_LINES_AFTER,
    DEFAULT_NAME,
    DEFAULT_PORT,
    DEFAULT_TITLE_SIZE,
    DEFAULT_TYPE,
    DOMAIN,
    NOTIFY_TYPE_OPTIONS,
)

_LOGGER = logging.getLogger(__name__)


def _device_schema(defaults: dict[str, Any] | None = None) -> vol.Schema:
    defaults = defaults or {}
    return vol.Schema(
        {
            vol.Required(CONF_HOST, default=defaults.get(CONF_HOST, "")): str,
            vol.Optional(
                CONF_PORT, default=defaults.get(CONF_PORT, DEFAULT_PORT)
            ): int,
            vol.Optional(
                CONF_NAME, default=defaults.get(CONF_NAME, DEFAULT_NAME)
            ): str,
        }
    )


async def _async_validate_device(hass, host: str, port: int) -> None:
    """Raise SunmiPrinterError if the device can't be reached."""
    session = async_get_clientsession(hass)
    client = SunmiPrinterClient(session, host, port)
    await client.async_health_check()


class SunmiPrinterConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle a config flow for the Sunmi Printer integration."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            host = user_input[CONF_HOST]
            port = user_input[CONF_PORT]
            try:
                await _async_validate_device(self.hass, host, port)
            except SunmiPrinterConnectionError:
                errors["base"] = "cannot_connect"
            except SunmiPrinterError:
                errors["base"] = "unknown"
            except Exception:  # noqa: BLE001
                _LOGGER.exception("Unexpected error validating Sunmi Printer")
                errors["base"] = "unknown"
            else:
                await self.async_set_unique_id(f"{host}:{port}")
                self._abort_if_unique_id_configured()
                return self.async_create_entry(
                    title=user_input.get(CONF_NAME, DEFAULT_NAME),
                    data=user_input,
                )

        return self.async_show_form(
            step_id="user", data_schema=_device_schema(user_input), errors=errors
        )

    async def async_step_reconfigure(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        entry = self._get_reconfigure_entry()
        errors: dict[str, str] = {}
        if user_input is not None:
            host = user_input[CONF_HOST]
            port = user_input[CONF_PORT]
            try:
                await _async_validate_device(self.hass, host, port)
            except SunmiPrinterConnectionError:
                errors["base"] = "cannot_connect"
            except SunmiPrinterError:
                errors["base"] = "unknown"
            except Exception:  # noqa: BLE001
                _LOGGER.exception("Unexpected error validating Sunmi Printer")
                errors["base"] = "unknown"
            else:
                await self.async_set_unique_id(f"{host}:{port}")
                self._abort_if_unique_id_mismatch(reason="wrong_device")
                return self.async_update_reload_and_abort(
                    entry, data=user_input
                )

        return self.async_show_form(
            step_id="reconfigure",
            data_schema=_device_schema(user_input or entry.data),
            errors=errors,
        )

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return SunmiPrinterOptionsFlow()


class SunmiPrinterOptionsFlow(OptionsFlow):
    """Defaults used by the notify entity when a service call omits them."""

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data=user_input)

        options = self.config_entry.options
        schema = vol.Schema(
            {
                vol.Optional(
                    CONF_DEFAULT_TYPE,
                    default=options.get(CONF_DEFAULT_TYPE, DEFAULT_TYPE),
                ): selector.SelectSelector(
                    selector.SelectSelectorConfig(options=NOTIFY_TYPE_OPTIONS)
                ),
                vol.Optional(
                    CONF_DEFAULT_LINES_AFTER,
                    default=options.get(
                        CONF_DEFAULT_LINES_AFTER, DEFAULT_LINES_AFTER
                    ),
                ): selector.NumberSelector(
                    selector.NumberSelectorConfig(min=0, max=20, mode="box")
                ),
                vol.Optional(
                    CONF_DEFAULT_TITLE_SIZE,
                    default=options.get(
                        CONF_DEFAULT_TITLE_SIZE, DEFAULT_TITLE_SIZE
                    ),
                ): selector.NumberSelector(
                    selector.NumberSelectorConfig(min=8, max=120, mode="box")
                ),
                vol.Optional(
                    CONF_DEFAULT_CONTENT_SIZE,
                    default=options.get(
                        CONF_DEFAULT_CONTENT_SIZE, DEFAULT_CONTENT_SIZE
                    ),
                ): selector.NumberSelector(
                    selector.NumberSelectorConfig(min=8, max=120, mode="box")
                ),
            }
        )
        return self.async_show_form(step_id="init", data_schema=schema)
