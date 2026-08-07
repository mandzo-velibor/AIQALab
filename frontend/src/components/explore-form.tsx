"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

interface ExploreFormProps {
  onExplore: (url: string) => void;
  loading: boolean;
}

export function ExploreForm({ onExplore, loading }: ExploreFormProps) {
  const [url, setUrl] = useState("");

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (url.trim()) {
      onExplore(url.trim());
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Explore Application</CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit} className="flex gap-2">
          <Input
            type="url"
            placeholder="https://example.com"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            className="flex-1"
            required
          />
          <Button type="submit" disabled={loading}>
            {loading ? "Exploring..." : "Explore"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
