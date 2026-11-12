import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
});

// jsdom has no layout. ProseMirror (the rich-text editor) measures the cursor to scroll it into view after inserting
// content, so give ranges and text nodes empty rectangles instead of failing.
const emptyRects = () => Object.assign([], { item: () => null }) as unknown as DOMRectList;
const emptyRect = () => new DOMRect(0, 0, 0, 0);
for (const proto of [Range.prototype, Text.prototype] as unknown as Record<string, unknown>[]) {
  proto.getClientRects ??= emptyRects;
  proto.getBoundingClientRect ??= emptyRect;
}
