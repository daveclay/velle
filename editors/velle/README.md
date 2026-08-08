# Velle syntax highlighting

A TextMate grammar for Velle (`syntaxes/velle.tmLanguage.json`), packaged as a
VS Code-style extension so IntelliJ and VS Code can both load it directly.
The grammar follows `grammar.md`'s lexical layer: `--` comments, reserved
keywords, scalar types, uppercase-first shape names, duration literals
(`7 days`), and Kotlin-style text escapes.

## IntelliJ

1. Settings → Editor → TextMate Bundles
2. Click **+** and select this directory (`editors/velle`)
3. Apply. `.velle` files are now colored; re-open any that were already open.

This is a per-machine IDE setting — each contributor does it once. If IntelliJ
had previously prompted you to associate `*.velle` with another file type,
remove that association under Settings → Editor → File Types first.

## VS Code

Symlink (or copy) this directory into your extensions folder:

```
ln -s "$(pwd)/editors/velle" ~/.vscode/extensions/velle-syntax
```

Then reload VS Code.

## GitHub

GitHub can't load per-repo grammars; the repo's `.gitattributes` maps `*.velle`
to Haskell as a stand-in (`--` comments, `where`/`if`/`then`/`else`, and
capitalized type names all render sensibly). Tag markdown code fences the same
way: ` ```haskell `. Real support means submitting this grammar to
[github-linguist](https://github.com/github-linguist/linguist) once Velle
clears their in-the-wild usage bar.
