# Offline Rime grammar comparison

These are host results from librime 1.12.0 with the same Lua and Octagram
revisions as the installed Android engine. Each comparison uses an empty user
directory and the same 66 spellings. Both grammar files were observed in the
engine's memory mappings. The sample does not measure general typing accuracy.

| Setup | Full Pinyin first choice |
| --- | --- |
| Packaged Lotem grammar | 49/66 |
| Wanxiang LTS, user-requested 5/2/−4 parameters, plus zhwiki | 55/66 |
| Wanxiang LTS, upstream recommended parameters (comparison only) | 56/66 |

The requested setup fixes examples such as 「明天请你帮我回复邮件」 and
「东方明珠广播电视塔」 as first choices, but changes 「明天上交吗」 to
「明天上叫骂」 in one office sentence. The requested long sample
「充满激情的跋涉远比到达目的地更能给人带来乐趣」 still ends in
「带来了去」 under the requested parameters. The upstream-parameter
comparison returned the intended sentence. The build retains the requested
parameters, so the comparison can inform subsequent tuning.

In T9, 「里斯本地铁」「东方明珠广播电视塔」「圣佩德罗德阿塔卡马」
went from zero of three to three of three first choices. The same host ran
the T9 composition, backspace, candidate selection and cancellation checks.
