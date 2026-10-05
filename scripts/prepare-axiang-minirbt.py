#!/usr/bin/env python3
"""Export the pinned, pretrained MiniRBT MLM as a small candidate-scoring model.

This is an experiment using pretrained masked-language-model weights, not a
fine-tuned input-method model. Models are written outside the source tree.
Install torch==2.6.0 (CPU), transformers==4.48.3, onnx==1.17.0,
onnxruntime==1.23.2 and numpy==2.2.3 in a separate virtual environment.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile


MODEL_ID = 'hfl/minirbt-h256'
REVISION = '446e1b4012b10946f479563005700c54c9db3f7c'
LICENSE_REVISION = 'baef787a1cee4fdc4051083943a460807e7b8a86'
SOURCE_SHA256 = {
    'config.json': '02cc77ba23f292bf5265d76fff662ae8a8057bf85ee9723ba30342bf9ed30746',
    'README.md': '667be60e0b89d63d308d3d1e76f906d056ad4a2c56e6a2f785544098e220f3eb',
    'vocab.txt': '45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c',
    'pytorch_model.bin': '6728ca573fd5197f28198eb7e6119a76e4d5dc626f953fca8c0dc5ba2798f9fc',
    'LICENSE': 'c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4',
}
INPUT_NAMES = ['input_ids', 'attention_mask', 'token_type_ids', 'masked_position', 'target_id']
OUTPUT_NAME = 'log_prob'
MAX_SEQUENCE_LENGTH = 96


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def fetch_source(cache, offline=False):
    cache.mkdir(parents=True, exist_ok=True)
    for name, expected in SOURCE_SHA256.items():
        destination = cache / name
        if destination.is_file() and sha256(destination) == expected:
            continue
        if offline:
            raise ValueError(f'Missing or corrupt pinned source file: {destination}')
        url = (f'https://raw.githubusercontent.com/iflytek/MiniRBT/{LICENSE_REVISION}/LICENSE'
               if name == 'LICENSE' else
               f'https://huggingface.co/{MODEL_ID}/resolve/{REVISION}/{name}')
        temporary = destination.with_suffix(destination.suffix + '.download')
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as out:
            shutil.copyfileobj(response, out)
        if sha256(temporary) != expected:
            temporary.unlink()
            raise ValueError(f'Pinned source checksum mismatch: {name}')
        temporary.replace(destination)


def export(cache, output, quantize=True):
    import onnx
    import torch
    from transformers import BertConfig, BertForMaskedLM

    torch.set_num_threads(1)
    config = BertConfig.from_json_file(str(cache / 'config.json'))
    config._attn_implementation = 'eager'
    model = BertForMaskedLM(config)
    weights = torch.load(cache / 'pytorch_model.bin', map_location='cpu', weights_only=True)
    required = ['cls.predictions.transform.dense.weight', 'cls.predictions.transform.LayerNorm.weight',
                'cls.predictions.decoder.weight', 'cls.predictions.bias']
    if any(name not in weights for name in required):
        raise ValueError('The official checkpoint does not contain a trained MLM head')
    # Older Transformers saved nonpersistent position buffers. They are not weights.
    weights = {name: value for name, value in weights.items()
               if name not in {'bert.embeddings.position_ids', 'bert.embeddings.token_type_ids'}}
    model.load_state_dict(weights, strict=True)
    model.eval()

    class MaskLogProbability(torch.nn.Module):
        def __init__(self, pretrained):
            super().__init__()
            self.bert = pretrained.bert
            self.predictions = pretrained.cls.predictions

        def forward(self, input_ids, attention_mask, token_type_ids, masked_position, target_id):
            hidden = self.bert(input_ids=input_ids, attention_mask=attention_mask,
                               token_type_ids=token_type_ids, return_dict=False)[0]
            index = masked_position.reshape(-1, 1, 1).expand(-1, 1, hidden.shape[-1])
            selected = hidden.gather(1, index).squeeze(1)
            # Select the masked hidden state before the vocabulary projection:
            # no [batch, sequence, vocabulary] tensor is ever created.
            logits = self.predictions(selected)
            target = logits.gather(1, target_id.reshape(-1, 1)).squeeze(1)
            return target - torch.logsumexp(logits, dim=1)

    scoring = MaskLogProbability(model).eval()
    ids = torch.tensor([[101, 872, 103, 102], [101, 103, 1962, 102]], dtype=torch.int64)
    sample = (ids, torch.ones_like(ids), torch.zeros_like(ids),
              torch.tensor([2, 1]), torch.tensor([1962, 872]))
    reference = output / 'model.fp32.onnx'
    with torch.inference_mode():
        torch.onnx.export(scoring, sample, str(reference), input_names=INPUT_NAMES,
                          output_names=[OUTPUT_NAME], opset_version=17, dynamo=False,
                          dynamic_axes={**{name: {0: 'batch', 1: 'sequence'} for name in INPUT_NAMES[:3]},
                                        **{name: {0: 'batch'} for name in INPUT_NAMES[3:]},
                                        OUTPUT_NAME: {0: 'batch'}})
    onnx.checker.check_model(str(reference))
    if not quantize:
        return scoring, sum(parameter.numel() for parameter in model.parameters())
    from onnxruntime.quantization import quantize_dynamic, QuantType
    # The pretrained decoder shares its weight with the word embeddings. ORT's
    # dynamic Gemm conversion transposes that initializer in place; giving the
    # decoder an independent initializer prevents corrupting the Gather table.
    quantization_graph = onnx.load(str(reference))
    initializers = {item.name: item for item in quantization_graph.graph.initializer}
    embedding_weight = 'bert.embeddings.word_embeddings.weight'
    for node in quantization_graph.graph.node:
        if node.op_type == 'Gemm' and embedding_weight in node.input:
            decoder_weight = copy.deepcopy(initializers[embedding_weight])
            decoder_weight.name = embedding_weight + '.mlm_decoder'
            quantization_graph.graph.initializer.append(decoder_weight)
            node.input[1] = decoder_weight.name
    quantization_input = output / 'model.quantization-input.onnx'
    onnx.save(quantization_graph, str(quantization_input))
    quantized = output / 'model.int8.onnx'
    quantize_dynamic(str(quantization_input), str(quantized), per_channel=True,
                     weight_type=QuantType.QInt8, op_types_to_quantize=['MatMul', 'Gather'])
    onnx.checker.check_model(str(quantized))
    return scoring, sum(parameter.numel() for parameter in model.parameters())


def deterministic_zip(directory, archive):
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for path in sorted(directory.iterdir()):
            if not path.is_file():
                raise ValueError(f'Unexpected bundle directory: {path}')
            info = zipfile.ZipInfo(path.name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            bundle.writestr(info, path.read_bytes())


def vocabulary_lines(path):
    # The official vocabulary contains Unicode separator tokens. Only LF/CRLF
    # delimit file records; str.splitlines() would silently insert two entries.
    lines = path.read_text(encoding='utf-8').split('\n')
    if lines and lines[-1] == '':
        lines.pop()
    return [line.removesuffix('\r') for line in lines]


def frozen_bundle(args, output):
    """Reproduce reviewed bytes without regenerating variable timing metadata."""
    manifest_bytes = args.frozen_manifest.read_bytes()
    manifest = json.loads(manifest_bytes)
    reference_report = args.frozen_manifest.with_name('verification-fp32.json')
    assert sha256(reference_report) == manifest['validation']['report_sha256']
    assert json.loads(reference_report.read_text())['passed'] is True
    assert manifest['format_version'] == 1 and manifest['model_id'] == MODEL_ID
    assert manifest['revision'] == REVISION and manifest['variant'] == 'fp32'
    assert manifest['license'] == 'Apache-2.0' and manifest['source_sha256'] == SOURCE_SHA256
    assert manifest['fine_tuned_for_ime'] is False
    vocabulary = vocabulary_lines(args.source_cache / 'vocab.txt')
    config = json.loads((args.source_cache / 'config.json').read_text())
    assert len(vocabulary) == config['vocab_size'] == manifest['vocabulary_size'] == 21128
    assert manifest['checkpoint_vocabulary_size'] == 21128
    assert manifest['special_tokens'] == {token: vocabulary.index(token)
                                          for token in ['[PAD]', '[UNK]', '[CLS]', '[SEP]', '[MASK]']}
    _, parameters = export(args.source_cache, output, quantize=False)
    assert parameters == manifest['parameters']
    bundle_dir = output / 'bundle'
    bundle_dir.mkdir(exist_ok=True)
    mappings = {'model.onnx': output / 'model.fp32.onnx', 'vocab.txt': args.source_cache / 'vocab.txt',
                'LICENSE': args.source_cache / 'LICENSE', 'MODEL_CARD.md': args.source_cache / 'README.md'}
    assert set(mappings) == set(manifest['files'])
    for name, source in mappings.items():
        expected = manifest['files'][name]
        assert sha256(source) == expected['sha256'] and source.stat().st_size == expected['bytes'], name
        shutil.copyfile(source, bundle_dir / name)
    (bundle_dir / 'manifest.json').write_bytes(manifest_bytes)
    assert {path.name for path in bundle_dir.iterdir()} == {*mappings, 'manifest.json'}
    archive = args.bundle or output / 'axiang-minirbt-h256-mlm-fp32.zip'
    archive.parent.mkdir(parents=True, exist_ok=True)
    deterministic_zip(bundle_dir, archive)
    if args.expected_bundle_sha256:
        assert sha256(archive) == args.expected_bundle_sha256, 'Frozen bundle ZIP bytes differ'
    if args.bundle_assets is not None:
        args.bundle_assets.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(archive, args.bundle_assets / archive.name)
    summary = {'bundle': str(archive), 'sha256': sha256(archive), 'bytes': archive.stat().st_size,
               'manifest_sha256': sha256(bundle_dir / 'manifest.json'),
               'model_sha256': sha256(bundle_dir / 'model.onnx'),
               'model_bytes': (bundle_dir / 'model.onnx').stat().st_size, 'parameters': parameters,
               'reproduced_frozen_manifest': str(args.frozen_manifest)}
    (output / 'bundle-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-cache', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--variant', choices=['int8', 'fp32'], default='fp32')
    parser.add_argument('--bundle', type=Path, help='Explicit output ZIP path; default is in output-dir')
    parser.add_argument('--bundle-assets', type=Path,
                        help='Explicit test-only asset directory to copy the validated bundle into')
    parser.add_argument('--frozen-manifest', type=Path,
                        help='Reproduce the reviewed FP32 ZIP using exact frozen metadata instead of new timings')
    parser.add_argument('--expected-bundle-sha256', help='Required expected ZIP hash for a frozen reproduction')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output_dir.resolve()
    if output == root or root in output.parents:
        raise ValueError('Model output must be outside the source tree')
    output.mkdir(parents=True, exist_ok=True)
    fetch_source(args.source_cache, args.offline)
    if args.frozen_manifest is not None:
        if args.variant != 'fp32':
            raise ValueError('Frozen reproduction supports the reviewed FP32 variant only')
        if not args.expected_bundle_sha256:
            raise ValueError('Frozen reproduction requires --expected-bundle-sha256')
        frozen_bundle(args, output)
        return
    scoring, parameters = export(args.source_cache, output)
    spec = importlib.util.spec_from_file_location('check_minirbt', Path(__file__).with_name('check-axiang-minirbt.py'))
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    report = checker.check_models(output / 'model.fp32.onnx', output / 'model.int8.onnx',
                                  args.source_cache / 'vocab.txt', scoring)
    (output / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if not report['passed'] or (args.variant == 'int8' and not report['quantization_passed']):
        raise ValueError('Selected model failed real inference validation; bundle not produced')
    bundle_dir = output / 'bundle'
    if bundle_dir.exists():
        shutil.rmtree(bundle_dir)
    bundle_dir.mkdir()
    shutil.copyfile(output / f'model.{args.variant}.onnx', bundle_dir / 'model.onnx')
    for source_name, bundle_name in [('vocab.txt', 'vocab.txt'), ('LICENSE', 'LICENSE'), ('README.md', 'MODEL_CARD.md')]:
        shutil.copyfile(args.source_cache / source_name, bundle_dir / bundle_name)
    vocabulary = vocabulary_lines(bundle_dir / 'vocab.txt')
    config = json.loads((args.source_cache / 'config.json').read_text())
    assert len(vocabulary) == config['vocab_size'] == 21128
    manifest = {
        'format_version': 1, 'model_id': MODEL_ID, 'revision': REVISION,
        'license': 'Apache-2.0', 'license_source_revision': LICENSE_REVISION,
        'experiment': 'pretrained-mlm-pseudo-log-likelihood', 'fine_tuned_for_ime': False,
        'variant': args.variant, 'parameters': parameters, 'onnx_opset': 17,
        'max_sequence_length': MAX_SEQUENCE_LENGTH, 'max_context_tokens': 48,
        'input_names': INPUT_NAMES, 'input_type': 'int64', 'output_name': OUTPUT_NAME,
        'output_type': 'float32', 'output_shape': ['batch'],
        'vocabulary_size': len(vocabulary),
        'checkpoint_vocabulary_size': config['vocab_size'],
        'special_tokens': {token: vocabulary.index(token) for token in ['[PAD]', '[UNK]', '[CLS]', '[SEP]', '[MASK]']},
        'candidate_score': 'mean masked-token log probability; mask candidate tokens only',
        'source_sha256': SOURCE_SHA256,
        'files': {path.name: {'sha256': sha256(path), 'bytes': path.stat().st_size}
                  for path in sorted(bundle_dir.iterdir())},
        'validation': {'host_only': True, 'report_sha256': sha256(output / 'verification.json'),
                       'fp32_torch_max_abs_error': report['fp32_torch_max_abs_error'],
                       'int8_fp32_max_abs_error': report['int8_fp32_max_abs_error'],
                       'top_candidate_agreement': report['top_candidate_agreement']},
    }
    (bundle_dir / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    archive = args.bundle or output / f'axiang-minirbt-h256-mlm-{args.variant}.zip'
    archive.parent.mkdir(parents=True, exist_ok=True)
    deterministic_zip(bundle_dir, archive)
    if args.bundle_assets is not None:
        args.bundle_assets.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(archive, args.bundle_assets / archive.name)
    summary = {'bundle': str(archive), 'sha256': sha256(archive), 'bytes': archive.stat().st_size,
               'manifest_sha256': sha256(bundle_dir / 'manifest.json'), 'model_sha256': sha256(bundle_dir / 'model.onnx'),
               'model_bytes': (bundle_dir / 'model.onnx').stat().st_size, 'parameters': parameters,
               'verification': str(output / 'verification.json')}
    (output / 'bundle-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
