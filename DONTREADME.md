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

## Prompting

> Is the prompt I specify a system prompt or a user prompt?

The prompt is a system prompt.

> Does `word` modify the system prompt before passing it to the model?

No. The model receives the exact string defined in your configuration.

I could've built `word` to tack on mandatory instructions at the beginning or end. But that would force you into a specific prompt structure.

> Does `word` pass the previous sentence to the model if it exists?

Yes. If a previous or following sentence exists, the tool includes it as context. This helps the model maintain flow and consistency.

## Requesting

> Can `⌘ + f` generate suggestions if your cursor is not inside a sentence?

Yes. If you hit `⌘ + f` while your cursor is between sentences, the plugin targets the next available sentence. This makes requesting suggestions faster. But you might accidentally target the wrong sentence. It's a blessing and a cursor.

> Does `word` cancel a pending LLM API request for a sentence if a new one is made for that same sentence?

No. Since it's unlikely the request content has changed in that tiny window between requests, `word` sticks with whichever response arrives first.

## Generating

> What model does `word` use?

`word` uses the `gpt-oss-120b` model.

I went through the most popular models on OpenRouter in order of popularity. My goal was to find something that could actually crank out at least 100 theoretical tokens in that very first second. I used this formula for theoretical tokens in the first second:

$$(1.0s - \text{latency}) \times \text{throughput}$$

`gpt-oss-120b` was the first one that cleared that bar. I chose Groq as the provider over Cerebras because:

- Groq had lower latency than Cerebras.

- It looked like Cerebras locked this model behind a paywall. I kept hitting 404 errors. Groq let me use this model without those hoops.

The other providers of `gpt-oss-120b` had higher latency and lower throughput than Groq.

## Highlighting

> Does `word` show an indicator on the targeted sentence while a request is pending?

Yes. `word` applies a highlight group to the targeted sentence.

> What highlight group is used for pending requests?

`DiagnosticUnderlineWarn` is used. The result could be a pass or a fail.

> Does `word` show a pass or fail indicator on the targeted sentence?

Yes. The plugin applies highlight groups to the targeted sentence.

It's faster to digest a binary indicator than a wall of text.

Highlighting the sentence allows you to scan for issues across multiple sentences visible in the buffer without moving your cursor to each sentence.

> What highlight group is used when a sentence fails?

`DiagnosticUnderlineError` is used.

> What highlight group is used when a sentence passes?

`DiagnosticUnderlineOk` is used. It's the semantic opposite of an error.

> Can editing a sentence clear its fail indicator?

Yes. If you edit a sentence that has a fail indicator and your change causes the text to no longer match the text of the sentence when suggestions were requested, the fail indicator is removed.

> Does applying a suggestion change a fail indicator to a pass indicator?

Yes. When you apply one of the currently cached suggestions to a sentence with a fail indicator, the fail indicator is replaced by a pass indicator.

> Does `word` show an indicator on the sentence you can apply a suggestion to without moving the cursor?

Yes. `word` applies a highlight group to the targeted sentence.

> What highlight group is used for indicating the sentence you can apply a suggestion to without moving the cursor?

`LspReferenceText` is used. Most themes don't render this group as an underline. This allows the target indicator to sit on top of the `DiagnosticUnderlineWarn`, `DiagnosticUnderlineError`, and `DiagnosticUnderlineOk` highlights.

## Displaying

> Is the Heads-Up Display (HUD) a floating window?

No. Floating windows allow the cursor to move behind them. I could try to fix that by programmatically forcing the main window to scroll. But that's a heavy-handed solution.

> Is the HUD a split window?

Yes. I chose this architecture because:

- If your cursor happens to overlap with the new window, Neovim pushes the text up so the content remains visible.

- If the cursor isn't in the way, I can use `winsaveview` and `winrestview` to keep the visible lines in the same position on your screen.

- You can jump in and out of the HUD using the keybindings you already use.

There are a couple of trade-offs with this approach:

- Opening a window and calling `winrestview` can cause a flicker.

- Neovim reserves one line for a status line, which results in a wasted line of screen real estate.

> Is the HUD a horizontal split or a vertical split?

The HUD is a horizontal split. Going vertical would waste screen real estate for a HUD that only needs a few lines.

> When the HUD opens while your cursor is in the top window of a stacked layout, does the HUD pop up under the top or bottom window?

The HUD shows up below the top window. I designed the HUD that way to keep the suggestions in proximity to the text you're editing.

> Can the content of the HUD change while you are in Insert mode?

Yes. If an asynchronous request from a command like `⌘ + f` finishes while you're typing, the HUD will update with those fresh suggestions. But the display stays anchored to the specific `extmark` you were on when you entered Insert mode. This lets you use the suggestions as a reference while you're editing the sentence. The HUD won't switch its focus to a different `extmark` just because you moved your cursor in Insert mode.

> Can the HUD show suggestions for a sentence my cursor isn't inside?

Yes. The HUD shows suggestions for whichever sentence `⌘ + f` would target if you triggered it now. To make this happen, the plugin defines an active range where those suggestions stay visible. If there's a previous sentence, this range starts at the character right after it ends. If there isn't a previous sentence, the range starts at the beginning of the file. In either case, it extends to the end of the targeted sentence. This range will get recalculated when the text it covers is edited.

> Does `word` explain why a sentence fails the requirements?

Yes. This helps you spot your patterns.

Sometimes you might intentionally write a messy draft to get suggestions. In those moments, you can ignore the explanation.

I could've designed the tool with a keybinding to request suggestions without an explanation. But choosing between two shortcuts would increase cognitive load.

> Does `word` explain why a sentence meets the requirements?

No. Sometimes you're making a specific style choice. You might want to confirm the sentence meets the requirements even if it looks like a mistake on paper. That's an edge case.

If you find yourself in that spot, you can hop into a standard LLM chat to provide more context and engage in a back-and-forth discussion.

> Does `word` provide suggestions if a sentence already meets the requirements?

Yes. Even if a sentence passes, the tool will still throw out two alternatives to help you see if there's a better way to express the thought.

## Caching

> Does `word` cache the suggestions it generates?

Yes. The cache holds the two most recent suggestions for each sentence.

> Does a response overwrite cached suggestions?

No. This prevents suggestions from swapping out while you are reading them.

When `word` makes a request, it clears any existing suggestions that overlap with the range where the new ones will appear. The first response that makes it back saves its data into that now-empty cache. Subsequent responses won't change cached suggestions.

> Can suggestions that target a deleted sentence reappear if I undo the deletion?

Yes. Neovim's native undo tree restores the `extmark`. That mark stays linked to the existing cache entry.

> Will suggestions for one sentence overwrite those for an identical sentence elsewhere?

No. `word` does not use the sentence's text as a cache key. Instead, it uses a Neovim extmark to pin suggestions to that specific instance of the sentence.

> Does applying a suggestion clear it from the cache?

No. After you apply a suggestion, the cache persists. This makes sure all the options you generated for that specific sentence stay available even after the text changes.

> Will I see my previous suggestions if I reopen a file?

No. The cache lives and dies with your editing session. Persistent caching would be unreliable if you:

- Edited the file outside of Neovim.

- Moved the file.

- Recovered from a swap file.
