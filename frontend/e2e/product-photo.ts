import type { Page } from "@playwright/test";

export async function productPhoto(page: Page, tint: string) {
  const picture = await page.evaluate((color) => {
    const canvas = document.createElement("canvas");
    canvas.width = 320;
    canvas.height = 280;
    const context = canvas.getContext("2d")!;
    context.fillStyle = "#ebf2f8";
    context.fillRect(0, 0, 320, 280);
    context.fillStyle = color;
    context.beginPath();
    [[110, 45], [136, 38], [148, 55], [172, 55], [184, 38], [210, 45], [261, 95], [224, 133],
      [204, 116], [204, 236], [116, 236], [116, 116], [96, 133], [59, 95]]
      .forEach(([x, y]) => context.lineTo(x, y));
    context.closePath();
    context.fill();
    return canvas.toDataURL("image/png").split(",")[1];
  }, tint);
  return Buffer.from(picture, "base64");
}
