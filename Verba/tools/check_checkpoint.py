"""Make sure a downloaded Qwen3-ASR checkpoint loads completely before converting it.

If the weight names did not match the converter's model code, transformers
would silently fill the gaps with random numbers and the converted model
would print nonsense. This fails loudly instead.

usage: python check_checkpoint.py CHECKPOINT_DIR CONVERTER_DIR
"""

import sys

sys.path.insert(0, sys.argv[2])

from transformers import AutoConfig, AutoModel  # noqa: E402
from qwen3_asr import Qwen3ASRConfig, Qwen3ASRForConditionalGeneration  # noqa: E402

AutoConfig.register("qwen3_asr", Qwen3ASRConfig)
AutoModel.register(Qwen3ASRConfig, Qwen3ASRForConditionalGeneration)

model, info = AutoModel.from_pretrained(sys.argv[1], output_loading_info=True)
missing = [k for k in info.get("missing_keys", []) if not k.endswith("lm_head.weight")]
unexpected = info.get("unexpected_keys", [])
n = sum(p.numel() for p in model.parameters())
print(f"{n / 1e6:.0f}M parameters; missing {len(missing)}, unexpected {len(unexpected)}")
if missing:
    print("::error::Weights missing from the checkpoint: " + ", ".join(missing[:10]))
    sys.exit(1)
if len(unexpected) > 10:
    print("::error::Checkpoint has weights the converter does not know: " + ", ".join(unexpected[:10]))
    sys.exit(1)
