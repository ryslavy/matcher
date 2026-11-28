## Summary

<!-- Briefly describe what this PR changes and why. -->

Closes #<!-- issue number, if applicable -->

## Type of Change

- [ ] Bug fix (non-breaking change fixing an issue)
- [ ] New feature or feature toggle
- [ ] Translation / localization improvement (`patches/locales/` or `patches/addons/`)
- [ ] Add-on `GlassKit` styling or upstream add-on patch
- [ ] Device preset, remote keymap, or theme
- [ ] Documentation, build, or CI improvement

## Device & Test Environment

- **TV / Box Model:** <!-- e.g., Xiaomi TV Box S 3rd Gen (MiTV-AFMU0), Shield TV, Chromecast, or JVM tests only -->
- **Android Version / SDK:** <!-- e.g., Android 14 / SDK 34 -->
- **Tested Visual Modes (if UI changed):** <!-- e.g., Frosted Tonal, Frost White, Pure Black, Sharp Home, Sharp Nested Menus -->

## Checklist

- [ ] `./gradlew testReleaseUnitTest assembleRelease --no-daemon` passes cleanly (all JVM unit tests green, including `LocaleIntegrityTest` and `TileFitTest`)
- [ ] If add-on locales or resources changed: `python tools/validate-addon-locales.py --merged-only` passes (`248/248`)
- [ ] All modified files inside `cleanyourtv/` are mirrored back into `patches/` (and `GlassKit.java` is synced identically across all 8 add-ons except line 1 `package ...;`, saved as UTF-8 without BOM)
- [ ] `patches/appletv-frosted-glass.patch` has been regenerated (if upstream tracked files were modified)
- [ ] Liquid Glass shader changes preserve valid premultiplied alpha (`0 <= rgb <= a <= 1`) and zero wallpaper leakage over external/DRM apps
- [ ] No personal LAN IPs, hometown coordinates, device dumps, keystores, or API keys are included (including in screenshots)
- [ ] Relevant documentation (`README.md`, `CHANGELOG.md`, `docs/README-cs.md`, `AGENTS.md`, or `SKILL.md`) has been updated if user-facing behavior or invariants changed
