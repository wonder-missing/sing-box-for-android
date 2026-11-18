# Conductor capability scenarios

This is the regression list for capabilities that span tools, the message stream
and workspace files. Before removing or merging a tool, or changing a tool
parameter, check the scenarios that name it and update the evidence.

## Image and resource flow

| ID | User-visible result | Tools / path | Evidence | Removal impact |
| --- | --- | --- | --- | --- |
| `image.inline_svg` | A fenced `svg` block or raw `<svg>` in an assistant reply becomes a rendered SVG card. | Assistant Markdown + AndroidSVG; no tool | `ConductorAssistantImageTest`, `a332757` | Removing the SVG parser or renderer breaks diagram replies. |
| `image.remote_markdown` | A remote image URL in Markdown, including a URL found by web search, becomes a tap-to-load image card. | `![alt](https://...)`; HTTPS/public-host loader | `b53a0ea`, local 592 tests | Removing Markdown image parsing or the remote loader breaks the check. |
| `image.download_then_reference` | The agent downloads an SVG or image to `conductor/resources/` and references the local path; the body is not returned to the model. | `http_fetch(output_path=..., overwrite?)` + `![alt](conductor/resources/...)` | `AgentToolsTest`/`ConductorFileStore` scenarios; device check | Removing local resource resolution breaks the context-saving image flow. |
| `file.download_generic` | The agent downloads any HTTP response body (text, JSON, binary) to `conductor/resources/` and gets path, size and hash metadata instead of the body. | `http_fetch(output_path=..., overwrite?, max_bytes?)`; any method and content type | `AgentToolsTest.httpFetchCanSaveANonImageResourceWithAnyMethod` | Removing `http_fetch.output_path` or restricting it to images/SVG breaks generic file downloads. |

## General rule

- Add a scenario when a combination first works on a device or in tests.
- Record the exact tool names and parameters, not only the UI result.
- A tool removal must either preserve the scenario through another path or
  explicitly retire the scenario with the owner's approval.
- Prefer extending an existing tool parameter over adding a new tool. The
  download flow is the reference: one `output_path` parameter instead of a
  `file_download` tool.
- `http_fetch.output_path` is generic file storage. Image and SVG rendering in
  Markdown is one consumer of a saved resource, not a restriction on what the
  tool may download.
