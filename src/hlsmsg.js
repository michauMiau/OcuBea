// OcuBea WebUI — hls.js error codes in something a person can act on.
//
// hls.js reports its ErrorDetails as camelCase tokens like "manifestLoadError"
// and "fragLoadError". Shown raw they read as a malfunction in the phone
// rather than as something with a known cause, so they are mapped here.
//
// A token that is not in the table is passed through unchanged on purpose:
// hiding an unknown code would make a new failure look like a known one.

import { t } from './i18n.js';

const BY_DETAIL = {
  manifestLoadError: () => t('hlsManifest'),
  manifestLoadTimeOut: () => t('hlsManifest'),
  manifestParsingError: () => t('hlsManifest'),
  manifestIncompatibleCodecsError: () => t('hlsCodec'),
  levelLoadError: () => t('hlsManifest'),
  levelLoadTimeOut: () => t('hlsManifest'),
  fragLoadError: () => t('hlsFragment'),
  fragLoadTimeOut: () => t('hlsFragment'),
  fragParsingError: () => t('hlsFragment'),
  // keyFrameNotFound does not exist in hls.js 1.5.x; bufferIncompatibleCodecs is
  // the one that fires when the decoder configuration in avcC is refused.
  bufferIncompatibleCodecsError: () => t('hlsCodec'),
  mediaSourceRequiresReset: () => t('hlsReset'),
  bufferAddCodecError: () => t('hlsCodec'),
  bufferAppendError: () => t('hlsBuffer'),
  bufferFullError: () => t('hlsBuffer'),
  bufferStalledError: () => t('hlsBuffer'),
  internalException: () => t('hlsInternal'),
  // The state the phone reports while the encoder is still warming up.
  hls_warming: () => t('hlsWarming'),
};

export const hlsMessage = (detail) => {
  const f = BY_DETAIL[detail];
  return f ? f() : detail;
};
