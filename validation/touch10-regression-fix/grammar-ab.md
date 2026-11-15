# Offline Rime grammar comparison

These are host results from librime 1.12.0 with the same Lua and Octagram
revisions as the installed Android engine. Each comparison uses an empty user
directory and the same [66 spellings](../../scripts/rime/lexicon-sentences.tsv).
Both grammar files were observed in the engine's memory mappings. These counts
describe the fixed sample, not general typing accuracy.

| Setup | Full Pinyin first choice | In first five |
| --- | --- | --- |
| Original packaged Lotem grammar | 49/66 | 50/66 |
| Previous test APK: Wanxiang LTS, max/min 5/2, non-collocation penalty −4, with zhwiki | 55/66 | 55/66 |
| Earlier Wanxiang comparison: max/min 6/2, penalties −14/−6/−100/−18 | 56/66 | 56/66 |
| Review settings: Wanxiang LTS with zhwiki, collocation penalty −16 | 57/66 | 57/66 |
| Current settings: same review profile, collocation penalty −18 | **57/66** | **57/66** |

The current precompiled schemas keep the review settings except for the
collocation penalty, tuned for this dictionary and test set:

```yaml
grammar:
  language: wanxiang-lts-zh-hans
  collocation_max_length: 8
  collocation_min_length: 2
  collocation_penalty: -18
  non_collocation_penalty: -8
  weak_collocation_penalty: -100
  rear_penalty: -20
```

`translator/contextual_suggestions` remains `true` for both layouts.
The build logs the settings, first choice for each of the 66 cases, and
aggregate Top 1 and Top 5 counts. The `rime-sentence-ab.json` artifact also
records up to five candidates per case.

Compared with the previous test APK, the current settings recover two
first choices: 「充满激情的跋涉远比到达目的地更能给人带来乐趣」
and 「整个计划还要再调整一下」. No previously correct first choice regressed.
The office sentence ending in 「明天上交吗」 still comes out as 「明天上叫骂」.

For the follow-up tuning, 14 alternatives ran on the original 66 cases with
the same model, zhwiki, Lua, and dictionary tables. None improved the review
profile's 57/66. Three settings without an original Top 1 or Top 5 regression
were shortlisted before evaluating 60 independently prepared, non-overlapping
sentences of 9–22 characters:

| Setting relative to the review profile | Original Top 1 | Holdout Top 1 | Holdout gained / lost |
| --- | --- | --- | --- |
| Review profile (control) | 57/66 | 39/60 | 0 / 0 |
| Collocation penalty −15 | 57/66 | 39/60 | 0 / 0 |
| Collocation penalty −18 | 57/66 | **41/60** | 2 / 0 |
| Maximum collocation length 6 | 57/66 | 39/60 | 0 / 0 |

Top 5 equalled Top 1 on these runs. At −18, all original first choices stayed
the same, including the requested long sentence. The two extra holdout hits
were 「这段路修好以后附近的居民坐公交车就方便多了」 (long sentences
5/8 to 6/8) and 「我们约在深圳北站的进站口见面」 (places in context
3/4 to 4/4); the other five categories were unchanged. This is a small
observed gain on the holdout, not a general accuracy claim or a default
recommended by the model publisher.

In T9, 「里斯本地铁」「东方明珠广播电视塔」「圣佩德罗德阿塔卡马」
went from zero of three to three of three first choices. The same host ran
the T9 composition, backspace, candidate selection and cancellation checks.

The touch.10.4 review compared the same two fixed sets before changing the
sentence-end penalty. A sweep found that `rear_penalty: -17` restores
「这份报告需要明天上交吗」 while keeping the totals at 57/66 and
41/60. It trades away the previously correct 「先试试市场行情再说吧」,
however. The reviewed integer and fractional settings did not fix both.
The shipped test configuration therefore keeps `rear_penalty: -20`.
