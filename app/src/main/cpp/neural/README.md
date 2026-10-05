The ONNX Runtime C API header is from the official `v1.17.3` tag:
https://github.com/microsoft/onnxruntime/blob/v1.17.3/include/onnxruntime/core/session/onnxruntime_c_api.h

SHA-256: `7e47eb78563da119f740dc0ddfda96800e779e0bcbd169a9a49e9c10746c4cd5`.
The accompanying MIT license is retained. The bridge requests API version 17,
which is supported by the newer pinned runtime already bundled for speech.
No additional ONNX Runtime binary is linked or distributed.

The standalone bridge can also be compiled with the Android NDK for delivery
builds that reuse verified speech/Rime libraries. It accepts at most 48 masked
rows of 96 tokens, returns only target-token log probabilities, and never sends
text or tensors over the network.
