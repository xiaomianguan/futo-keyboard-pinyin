# fcitx5 pinyin data

The fcitx5 (libime) pinyin engine needs two read-only data files, which are **not** committed
to this repository (they are large binary files):

| File        | Purpose                                                   |
|-------------|-----------------------------------------------------------|
| `sc.dict`   | The Simplified Chinese system dictionary (libime binary). |
| `zh_CN.lm`  | The Chinese language model used for sentence scoring.     |

Place both files in this directory (`java/assets/fcitx5-pinyin/`). They are extracted into the
app's private files directory on first use (see
`org.futo.inputmethod.engine.fcitx5.Fcitx5PinyinData`).

To obtain them automatically, run:

```sh
tools/fcitx5/fetch-pinyin-data.sh
```

If these files are missing, the engine reports itself as unavailable and the
"Simplified Chinese (pinyin) [fcitx5]" option falls back to plain text input; everything else
keeps working.
