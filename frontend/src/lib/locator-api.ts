export interface LocatorDto {
  id: number;
  elementName: string;
  elementType: string;
  preferredLocator: string;
  fallbackLocators: string[];
  strategy: string;
  confidence: number;
  reason: string;
}

export interface LocatorResponse {
  generated: number;
  locators: LocatorDto[];
}

export async function generateLocators(url: string): Promise<LocatorResponse> {
  const res = await fetch("http://localhost:8080/api/locators/generate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url }),
  });

  if (!res.ok) {
    throw new Error(`Locator generation failed: ${res.status}`);
  }

  return res.json();
}

export async function getLocators(url: string): Promise<LocatorDto[]> {
  const res = await fetch(`http://localhost:8080/api/locators?url=${encodeURIComponent(url)}`);

  if (!res.ok) {
    throw new Error(`Failed to fetch locators: ${res.status}`);
  }

  return res.json();
}
