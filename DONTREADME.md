# word

## Goals

### Compatibility

> Does this tool override default Vim keybindings?

No. The tool is designed to assist without ever getting in your way.

### Latency

> What's the latency goal for getting suggestions?

The goal is under 1.0 second.

[1.0 second is about the limit for the user's flow of thought to stay uninterrupted](https://www.nngroup.com/articles/response-times-3-important-limits/#:~:text=1.0%20second%20is%20about%20the%20limit%20for%20the%20user%27s%20flow%20of%20thought%20to%20stay%20uninterrupted).

### Cost

> What's the monthly cost goal?

The goal is under $1 a month. That's over 10x cheaper than [Grammarly Pro](https://www.grammarly.com/plans#:~:text=%2412,USD).

## Model

> What model does `word` use?

`word` uses the `gpt-oss-120b` model.

I went through the most popular models on OpenRouter in order of popularity. My goal was to find something that could actually crank out at least 100 theoretical tokens in that very first second. I used this formula for theoretical tokens in the first second:

$$(1.0s - \text{latency}) \times \text{throughput}$$

`gpt-oss-120b` was the first one that cleared that bar. I chose Groq as the provider over Cerebras because:

- Groq had lower latency than Cerebras.

- It looked like Cerebras locked this model behind a paywall. I kept hitting 404 errors. Groq let me use this model without those hoops.

The other providers of `gpt-oss-120b` had higher latency and lower throughput than Groq.

## Suggestion

> Can `⌘ + f` generate suggestions if your cursor is not inside a sentence?

Yes. If you hit `⌘ + f` while your cursor is between sentences, the plugin targets the next available sentence. This makes requesting suggestions faster. But you might accidentally target the wrong sentence. It's a blessing and a cursor.

> Does `word` pass the previous sentence to the model if it exists?

Yes. If a previous or following sentence exists, the tool includes it as context. This helps the model maintain flow and consistency.

## Heads-Up Display (HUD)

> Can the content of the HUD change while you are in Insert mode?

Yes. If an asynchronous request from a command like `⌘ + f` finishes while you're typing, the HUD will update with those fresh suggestions. But the display stays anchored to the specific `extmark` you were on when you entered Insert mode. This lets you use the suggestions as a reference while you're editing the sentence. The HUD won't switch its focus to a different `extmark` just because you moved your cursor in Insert mode.

> Can the HUD show suggestions for a sentence my cursor isn't inside?

Yes. The HUD shows suggestions for whichever sentence `⌘ + f` would target if you triggered it now. To make this happen, the plugin defines an active range where those suggestions stay visible. If there's a previous sentence, this range starts at the character right after it ends. If there isn't a previous sentence, the range starts at the beginning of the file. In either case, it extends to the end of the targeted sentence. This range will get recalculated when the text it covers is edited.

## Caching

> Does `word` cache the suggestions it generates?

Yes. The cache holds the two most recent suggestions for each sentence.

> Will suggestions for one sentence overwrite those for an identical sentence elsewhere?

No. `word` does not use the sentence's text as a cache key. Instead, it uses a Neovim extmark to pin suggestions to that specific instance of the sentence.

> Does applying a suggestion clear it from the cache?

No. After you apply a suggestion, the cache persists. This makes sure all the options you generated for that specific sentence stay available even after the text changes.

> Can editing one sentence delete the suggestions for another sentence?

Yes. If your edits merge multiple sentences into one, the suggestions for all merged sentences get cleared.

When an edit modifies text linked to an `extmark`, the plugin recalculates the grammatical boundaries starting from the start and end positions of that `extmark`. If those boundaries have expanded to overlap with other `extmarks`, the plugin wipes those suggestions from the cache. This keeps `word` from showing you stale data that was generated for the individual sentences before they were merged.

> Will I see my previous suggestions if I reopen a file?

No. The cache lives and dies with your editing session. Persistent caching would be unreliable if you:

- Edited the file outside of Neovim.

- Moved the file.

- Recovered from a swap file.
