package org.batfish.datamodel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class OspfExternalType2Route extends OspfExternalRoute {

  /** */
  private static final long serialVersionUID = 1L;

  @JsonCreator
  public OspfExternalType2Route(
      @JsonProperty(PROP_NETWORK) Prefix network,
      @JsonProperty(PROP_NEXT_HOP_IP) Ip nextHopIp,
      @JsonProperty(PROP_ADMINISTRATIVE_COST) int admin,
      @JsonProperty(PROP_METRIC) int metric,
      @JsonProperty(PROP_COST_TO_ADVERTISER) int costToAdvertiser,
      @JsonProperty(PROP_ADVERTISER) String advertiser) {
    super(network, nextHopIp, admin, metric, advertiser, costToAdvertiser);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!super.equals(obj)) {
      return false;
    }
    OspfExternalType2Route other = (OspfExternalType2Route) obj;
    if (getCostToAdvertiser() != other.getCostToAdvertiser()) {
      return false;
    }
    return true;
  }

  @Override
  public OspfMetricType getOspfMetricType() {
    return OspfMetricType.E2;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = prime * result + getCostToAdvertiser();
    return result;
  }

  @Override
  protected final String ospfExternalRouteString() {
    return " costToAdvertiser:" + getCostToAdvertiser();
  }

  @Override
  public int routeCompare(AbstractRoute rhs) {
    if (getClass() != rhs.getClass()) {
      return 0;
    }
    OspfExternalType2Route castRhs = (OspfExternalType2Route) rhs;
    int ret = Integer.compare(getCostToAdvertiser(), castRhs.getCostToAdvertiser());
    return ret;
  }
}
