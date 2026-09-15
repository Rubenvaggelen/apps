# The One — GitHub Android SDK setup fix

GitHub Actions currently fails in `android-actions/setup-android` with:

```
Warning: Failed to find package 'tools'
Error: ... sdkmanager ... failed with exit code 1
```

The setup action's default package list includes the legacy `tools` package. This patch changes every
`android-actions/setup-android@...` step in `.github/workflows/*.yml` / `*.yaml` so it contains:

```yaml
with:
  packages: ''
```

This leaves the rest of the workflow untouched.

Run from the repository root after extracting the update:

```bash
python3 the-one-update/fix_android_setup.py .
```
