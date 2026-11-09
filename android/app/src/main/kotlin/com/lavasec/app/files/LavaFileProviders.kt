package com.lavasec.app.files

import androidx.core.content.FileProvider

// Android caches provider instances by class identity in one process. Each private authority
// must therefore have its own concrete class; reusing FileProvider directly aliases authorities.
class LocalLogExportFileProvider : FileProvider()
class FilterQrCaptureFileProvider : FileProvider()
class FilterShareCardFileProvider : FileProvider()
