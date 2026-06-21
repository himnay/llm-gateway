package com.llm.gateway.llm_gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provenance pointer for a source document backing an answer, e.g. produced by an upstream RAG
 * service (llm-rag) and passed through the gateway untouched alongside the LLM response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Citation {

  private String source;

  @JsonProperty("file_name")
  private String fileName;

  private String identity;

  private Integer page;

  @JsonProperty("chunk_index")
  private Integer chunkIndex;

  private Double score;
}
