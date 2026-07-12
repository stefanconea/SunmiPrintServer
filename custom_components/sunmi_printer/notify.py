"""Notify entity and print_* services for the Sunmi Printer integration."""
from __future__ import annotations

import base64
import logging

import voluptuous as vol

from homeassistant.components.notify import NotifyEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_NAME, CONF_PORT
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv, entity_platform
from homeassistant.helpers.entity import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import (
    ALIGNMENT_OPTIONS,
    ALIGNMENT_TO_INT,
    CONF_DEFAULT_LINES_AFTER,
    CONF_DEFAULT_TYPE,
    DEFAULT_CONTENT_SIZE,
    DEFAULT_LINES_AFTER,
    DEFAULT_NAME,
    DEFAULT_TITLE_SIZE,
    DEFAULT_TYPE,
    DOMAIN,
    PRINT_TEXT_TYPE_OPTIONS,
    SERVICE_PRINT_ALERT,
    SERVICE_PRINT_BANNER,
    SERVICE_PRINT_BARCODE,
    SERVICE_PRINT_BOXED,
    SERVICE_PRINT_IMAGE,
    SERVICE_PRINT_LIST,
    SERVICE_PRINT_QR,
    SERVICE_PRINT_RAW,
    SERVICE_PRINT_TEXT,
)

_LOGGER = logging.getLogger(__name__)

_ALIGNMENT_VALIDATOR = vol.In(ALIGNMENT_OPTIONS)

PRINT_TEXT_SCHEMA = {
    vol.Optional("format", default=DEFAULT_TYPE): vol.In(PRINT_TEXT_TYPE_OPTIONS),
    vol.Optional("title"): cv.string,
    vol.Required("content"): cv.string,
    vol.Optional("title_size", default=DEFAULT_TITLE_SIZE): cv.positive_int,
    vol.Optional("content_size", default=DEFAULT_CONTENT_SIZE): cv.positive_int,
    vol.Optional("center_title", default=False): cv.boolean,
    vol.Optional("alignment", default="left"): _ALIGNMENT_VALIDATOR,
    vol.Optional("lines_after", default=DEFAULT_LINES_AFTER): cv.positive_int,
}

PRINT_BOXED_SCHEMA = {
    vol.Optional("title"): cv.string,
    vol.Required("content"): cv.string,
    vol.Optional("title_size", default=DEFAULT_TITLE_SIZE): cv.positive_int,
    vol.Optional("content_size", default=DEFAULT_CONTENT_SIZE): cv.positive_int,
    vol.Optional("center_title", default=False): cv.boolean,
    vol.Optional("alignment", default="left"): _ALIGNMENT_VALIDATOR,
    vol.Optional("lines_after", default=DEFAULT_LINES_AFTER): cv.positive_int,
}

PRINT_BANNER_SCHEMA = {
    vol.Required("content"): cv.string,
    vol.Optional("lines_after", default=DEFAULT_LINES_AFTER): cv.positive_int,
}

PRINT_LIST_SCHEMA = {
    vol.Optional("title"): cv.string,
    vol.Required("content"): cv.string,
    vol.Optional("content_size", default=DEFAULT_CONTENT_SIZE): cv.positive_int,
    vol.Optional("alignment", default="left"): _ALIGNMENT_VALIDATOR,
    vol.Optional("lines_after", default=DEFAULT_LINES_AFTER): cv.positive_int,
}

PRINT_ALERT_SCHEMA = {
    vol.Required("content"): cv.string,
    vol.Optional("timestamp"): cv.string,
}

PRINT_QR_SCHEMA = {
    vol.Required("content"): cv.string,
    vol.Optional("alignment", default="center"): _ALIGNMENT_VALIDATOR,
    vol.Optional("lines_after", default=DEFAULT_LINES_AFTER): cv.positive_int,
}

PRINT_BARCODE_SCHEMA = {
    vol.Required("content"): cv.string,
    vol.Optional("alignment", default="center"): _ALIGNMENT_VALIDATOR,
    vol.Optional("lines_after", default=DEFAULT_LINES_AFTER): cv.positive_int,
}

PRINT_IMAGE_SCHEMA = {
    vol.Optional("url"): cv.string,
    vol.Optional("image_entity"): cv.entity_id,
    vol.Optional("lines_after", default=DEFAULT_LINES_AFTER): cv.positive_int,
}

PRINT_RAW_SCHEMA = {
    vol.Required("type"): cv.string,
    vol.Optional("data", default=dict): dict,
}


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    """Set up the Sunmi Printer notify entity and its print_* services."""
    async_add_entities([SunmiPrinterNotifyEntity(entry)])

    platform = entity_platform.async_get_current_platform()
    platform.async_register_entity_service(
        SERVICE_PRINT_TEXT, PRINT_TEXT_SCHEMA, "async_print_text"
    )
    platform.async_register_entity_service(
        SERVICE_PRINT_BOXED, PRINT_BOXED_SCHEMA, "async_print_boxed"
    )
    platform.async_register_entity_service(
        SERVICE_PRINT_BANNER, PRINT_BANNER_SCHEMA, "async_print_banner"
    )
    platform.async_register_entity_service(
        SERVICE_PRINT_LIST, PRINT_LIST_SCHEMA, "async_print_list"
    )
    platform.async_register_entity_service(
        SERVICE_PRINT_ALERT, PRINT_ALERT_SCHEMA, "async_print_alert"
    )
    platform.async_register_entity_service(
        SERVICE_PRINT_QR, PRINT_QR_SCHEMA, "async_print_qr"
    )
    platform.async_register_entity_service(
        SERVICE_PRINT_BARCODE, PRINT_BARCODE_SCHEMA, "async_print_barcode"
    )
    platform.async_register_entity_service(
        SERVICE_PRINT_IMAGE, PRINT_IMAGE_SCHEMA, "async_print_image"
    )
    platform.async_register_entity_service(
        SERVICE_PRINT_RAW, PRINT_RAW_SCHEMA, "async_print_raw"
    )


class SunmiPrinterNotifyEntity(NotifyEntity):
    """Represents the printer as a notify entity, plus structured print_* services."""

    _attr_has_entity_name = True
    _attr_name = None

    def __init__(self, entry: ConfigEntry) -> None:
        self._entry = entry
        self._client = entry.runtime_data
        self._attr_unique_id = f"{entry.entry_id}_notify"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            name=entry.data.get(CONF_NAME, DEFAULT_NAME),
            manufacturer="Sunmi",
            model="V2 Pro (custom print server)",
            configuration_url=f"http://{entry.data[CONF_HOST]}:{entry.data[CONF_PORT]}/",
        )

    # -- notify.send_message ------------------------------------------------

    async def async_send_message(self, message: str, title: str | None = None) -> None:
        options = self._entry.options
        await self._client.async_print(
            {
                "type": options.get(CONF_DEFAULT_TYPE, DEFAULT_TYPE),
                "title": title,
                "content": message,
                "linesAfter": options.get(
                    CONF_DEFAULT_LINES_AFTER, DEFAULT_LINES_AFTER
                ),
            }
        )

    # -- sunmi_printer.print_* entity services -------------------------------

    async def async_print_text(
        self,
        format: str,
        content: str,
        title: str | None = None,
        title_size: int = DEFAULT_TITLE_SIZE,
        content_size: int = DEFAULT_CONTENT_SIZE,
        center_title: bool = False,
        alignment: str = "left",
        lines_after: int = DEFAULT_LINES_AFTER,
    ) -> None:
        await self._client.async_print(
            {
                "type": format,
                "title": title,
                "content": content,
                "titleSize": title_size,
                "contentSize": content_size,
                "centerTitle": center_title,
                "alignment": ALIGNMENT_TO_INT[alignment],
                "linesAfter": lines_after,
            }
        )

    async def async_print_boxed(
        self,
        content: str,
        title: str | None = None,
        title_size: int = DEFAULT_TITLE_SIZE,
        content_size: int = DEFAULT_CONTENT_SIZE,
        center_title: bool = False,
        alignment: str = "left",
        lines_after: int = DEFAULT_LINES_AFTER,
    ) -> None:
        await self._client.async_print(
            {
                "type": "boxed",
                "title": title,
                "content": content,
                "titleSize": title_size,
                "contentSize": content_size,
                "centerTitle": center_title,
                "alignment": ALIGNMENT_TO_INT[alignment],
                "linesAfter": lines_after,
            }
        )

    async def async_print_banner(
        self, content: str, lines_after: int = DEFAULT_LINES_AFTER
    ) -> None:
        await self._client.async_print(
            {"type": "banner", "content": content, "linesAfter": lines_after}
        )

    async def async_print_list(
        self,
        content: str,
        title: str | None = None,
        content_size: int = DEFAULT_CONTENT_SIZE,
        alignment: str = "left",
        lines_after: int = DEFAULT_LINES_AFTER,
    ) -> None:
        await self._client.async_print(
            {
                "type": "list",
                "title": title,
                "content": content,
                "contentSize": content_size,
                "alignment": ALIGNMENT_TO_INT[alignment],
                "linesAfter": lines_after,
            }
        )

    async def async_print_alert(
        self, content: str, timestamp: str | None = None
    ) -> None:
        await self._client.async_print(
            {"type": "alert", "content": content, "timestamp": timestamp}
        )

    async def async_print_qr(
        self,
        content: str,
        alignment: str = "center",
        lines_after: int = DEFAULT_LINES_AFTER,
    ) -> None:
        await self._client.async_print(
            {
                "type": "qr",
                "content": content,
                "alignment": ALIGNMENT_TO_INT[alignment],
                "linesAfter": lines_after,
            }
        )

    async def async_print_barcode(
        self,
        content: str,
        alignment: str = "center",
        lines_after: int = DEFAULT_LINES_AFTER,
    ) -> None:
        await self._client.async_print(
            {
                "type": "barcode",
                "content": content,
                "alignment": ALIGNMENT_TO_INT[alignment],
                "linesAfter": lines_after,
            }
        )

    async def async_print_image(
        self,
        url: str | None = None,
        image_entity: str | None = None,
        lines_after: int = DEFAULT_LINES_AFTER,
    ) -> None:
        if bool(url) == bool(image_entity):
            raise ServiceValidationError(
                "Specify exactly one of 'url' or 'image_entity'"
            )
        content = url if url else await self._async_fetch_entity_image_b64(image_entity)
        await self._client.async_print(
            {"type": "image", "content": content, "linesAfter": lines_after}
        )

    async def async_print_raw(self, type: str, data: dict | None = None) -> None:
        job = {"type": type}
        job.update(data or {})
        await self._client.async_print(job)

    # -- helpers --------------------------------------------------------------

    async def _async_fetch_entity_image_b64(self, entity_id: str) -> str:
        domain = entity_id.split(".", 1)[0]
        if domain == "camera":
            from homeassistant.components.camera import async_get_image

            image = await async_get_image(self.hass, entity_id)
        elif domain == "image":
            from homeassistant.components.image import async_get_image

            image = await async_get_image(self.hass, entity_id)
        else:
            raise ServiceValidationError(
                f"Entity {entity_id} is not a camera or image entity"
            )
        return base64.b64encode(image.content).decode("ascii")
